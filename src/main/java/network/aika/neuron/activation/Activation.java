package network.aika.neuron.activation;

import network.aika.Document;
import network.aika.Utils;
import network.aika.lattice.OrNode;
import network.aika.lattice.OrNode.OrActivation;
import network.aika.neuron.INeuron;
import network.aika.neuron.Neuron;
import network.aika.neuron.Synapse;
import network.aika.neuron.activation.SearchNode.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static network.aika.neuron.activation.SearchNode.Decision.SELECTED;
import static network.aika.neuron.activation.Activation.Link.INPUT_COMP;
import static network.aika.neuron.activation.Activation.Link.OUTPUT_COMP;
import static network.aika.neuron.INeuron.ALLOW_WEAK_NEGATIVE_WEIGHTS;


/**
 * The {@code Activation} class is the most central class in Aika. On the one hand it stores the activation value
 * for a given neuron in the {@code State} substructure. On the other hand it specifies where this activation is
 * located within the document and to which interpretation it belongs. The {@code Activation.Key} therefore
 * consists of the logic node to which this activation belongs. If this logic node is an or-node, then this activation
 * automatically also belongs to the neuron as well. Furthermore, the key contains the char range within the document
 * and the relational id (rid). The relational id might be used to store the word pos for instance. Lastly, the key
 * contain the interpretation node of this activation, specifying to which interpretation this activation belongs.
 *
 * <p>The activations are linked to each other on two levels. The fields {@code inputs} and {@code outputs}
 * contain the activation links within the logic layer. The fields {@code neuronInputs} and
 * {@code neuronOutputs} contain the links on the neural layer.
 *
 * @author Lukas Molzberger
 */
public final class Activation extends OrActivation {
    public static final Comparator<Activation> ACTIVATION_ID_COMP = Comparator.comparingInt(act -> act.id);
    public static int MAX_SELF_REFERENCING_DEPTH = 5;

    private static final Logger log = LoggerFactory.getLogger(Activation.class);

    public Range range;


    public TreeSet<Link> selectedNeuronInputs = new TreeSet<>(INPUT_COMP);
    public TreeMap<Link, Link> neuronInputs = new TreeMap<>(INPUT_COMP);
    public TreeSet<Link> neuronOutputs = new TreeSet<>(OUTPUT_COMP);

    public Integer sequence;

    public double upperBound;
    public double lowerBound;
    public double maxWeight;

    public Rounds rounds = new Rounds();
    public Rounds finalRounds = rounds;

    public boolean ubQueued = false;
    public boolean isQueued = false;
    public long queueId;
    public long markedHasCandidate;

    public long currentStateV;
    public StateChange currentStateChange;
    public long markedDirty;
    public long markedPredecessor;

    public double errorSignal;
    public Double targetValue;
    public Double inputValue;


    public Decision inputDecision = Decision.UNKNOWN;
    public Decision decision = Decision.UNKNOWN;
    public Decision finalDecision = Decision.UNKNOWN;
    public Candidate candidate;
    private long visitedState;
    public long markedAncestor;


    private List<Activation> conflicts;


    public Activation(int id, Document doc, OrNode n) {
        super(id, doc, n);
    }

    public Activation(int id, Document doc, Range r, OrNode n) {
        super(id, doc, n);
        this.range = r;
    }


    public void setTargetValue(Double targetValue) {
        this.targetValue = targetValue;
        if (targetValue != null) {
            doc.supervisedTraining.targetActivations.add(this);
        } else {
            doc.supervisedTraining.targetActivations.remove(this);
        }
    }


    public String getLabel() {
        return getINeuron().label;
    }


    public String getText() {
        return doc.getText(range);
    }


    public INeuron getINeuron() {
        return getNeuron().get(doc);
    }


    public Neuron getNeuron() {
        return node.neuron;
    }


    public void addSynapseActivation(Linker.Direction dir, Link sa) {
        switch(dir) {
            case INPUT:
                neuronOutputs.add(sa);
                break;
            case OUTPUT:
                if(sa.input.decision == SELECTED) {
                    selectedNeuronInputs.add(sa);
                }
                neuronInputs.put(sa, sa);
                break;
        }
    }


    public double process(SearchNode sn, int round, long v) {
        double delta = 0.0;
        State s;
        if(inputValue != null) {
            s = new State(inputValue, 0.0, 0, 0.0);
        } else {
            s = computeValueAndWeight(round);
        }

        if (round == 0 || !rounds.get(round).equalsWithWeights(s)) {
            saveOldState(sn.modifiedActs, v);

            State oldState = rounds.get(round);

            boolean propagate = rounds.set(round, s) && (oldState == null || !oldState.equals(s));

            saveNewState();

            if (propagate) {
                if(round > Document.MAX_ROUND) {
                    log.error("Error: Maximum number of rounds reached. The network might be oscillating.");
                    log.info(doc.activationsToString(false, true, true));

                    doc.dumpOscillatingActivations();
                    throw new RuntimeException("Maximum number of rounds reached. The network might be oscillating.");
                } else {
                    doc.vQueue.propagateActivationValue(round, this);
                }
            }

            if (round == 0) {
                // In case that there is a positive feedback loop.
                doc.vQueue.add(1, this);
            }

            if (rounds.getLastRound() != null && round >= rounds.getLastRound()) { // Consider only the final round.
                delta += s.weight - oldState.weight;
            }
        }
        return delta;
    }


    public State computeValueAndWeight(int round) {
        INeuron n = getINeuron();
        double net = n.biasSum;

        int fired = -1;

        long v = doc.visitedCounter++;
        markPredecessor(v);

        for (InputState is: getInputStates(round, v)) {
            Synapse s = is.l.synapse;
            Activation iAct = is.l.input;

            if (iAct == this) continue;

            double x = is.s.value * s.weight;
            if(s.distanceFunction != null) {
                x *= s.distanceFunction.f(iAct, this);
            }
            net += x;

            if (!s.key.isRecurrent && !s.isNegative() && net >= 0.0 && fired < 0) {
                fired = iAct.rounds.get(round).fired + 1;
            }
        }

        double currentActValue = n.activationFunction.f(net);

        double w = Math.min(-n.negRecSum, net);

        // Compute only the recurrent part is above the threshold.
        double newWeight = decision == SELECTED ? Math.max(0.0, w) : 0.0;

        maxWeight = Math.max(maxWeight, newWeight);

        if(decision == SELECTED || ALLOW_WEAK_NEGATIVE_WEIGHTS) {
            return new State(
                    currentActValue,
                    net,
                    -1,
                    newWeight
            );
        } else {
            return new State(
                    0.0,
                    0.0,
                    -1,
                    newWeight
            );
        }
    }


    public void processBounds() {
        double oldUpperBound = upperBound;

        computeBounds();

        if(Math.abs(upperBound - oldUpperBound) > 0.01) {
            for(Link l: neuronOutputs) {
                doc.ubQueue.add(l.output);
            }
        }

        if (oldUpperBound <= 0.0 && upperBound > 0.0) {
            getINeuron().propagate(this);
        }
    }


    public void computeBounds() {
        INeuron n = getINeuron();
        double ub = n.biasSum + n.posRecSum;
        double lb = n.biasSum + n.posRecSum;

        long v = doc.visitedCounter++;
        markPredecessor(v);

        for (Link l : neuronInputs.values()) {
            Synapse s = l.synapse;
            if(s.inactive) {
                continue;
            }

            Activation iAct = l.input;

            if (iAct == this) continue;

            double x = s.weight;
            if(s.distanceFunction != null) {
                x *= s.distanceFunction.f(iAct, this);
            }

            if (s.isNegative()) {
                if (!s.key.isRecurrent && !iAct.checkSelfReferencing(false, 0, v)) {
                    ub += iAct.lowerBound * x;
                }

                lb += x;
            } else {
                ub += iAct.upperBound * x;
                lb += iAct.lowerBound * x;
            }
        }

        upperBound = n.activationFunction.f(ub);
        lowerBound = n.activationFunction.f(lb);
    }


    private static State getInitialState(Decision c) {
        return new State(
                c == SELECTED ? 1.0 : 0.0,
                0.0,
                0,
                0.0
        );
    }



    private List<InputState> getInputStates(int round, long v) {
        ArrayList<InputState> tmp = new ArrayList<>();
        Synapse lastSynapse = null;
        InputState maxInputState = null;
        for (Link l : neuronInputs.values()) {
            if(l.synapse.inactive) {
                continue;
            }
            if (lastSynapse != null && lastSynapse != l.synapse) {
                tmp.add(maxInputState);
                maxInputState = null;
            }

            State s = l.input.getInputState(round, l.synapse, v);
            if (maxInputState == null || maxInputState.s.value < s.value) {
                maxInputState = new InputState(l, s);
            }
            lastSynapse = l.synapse;
        }
        if (maxInputState != null) {
            tmp.add(maxInputState);
        }

        return tmp;
    }


    private static class InputState {
        public InputState(Link l, State s) {
            this.l = l;
            this.s = s;
        }

        Link l;
        State s;
    }


    private State getInputState(int round, Synapse s, long v) {
        State is = State.ZERO;
        if (s.key.isRecurrent) {
            if (!s.isNegative() || !checkSelfReferencing(true, 0, v)) {
                is = round == 0 ? getInitialState(decision) : rounds.get(round - 1);
            }
        } else {
            is = rounds.get(round);
        }
        return is;
    }


    public List<Link> getFinalInputActivationLinks() {
        ArrayList<Link> results = new ArrayList<>();
        for (Link l : neuronInputs.values()) {
            if (l.input.isFinalActivation()) {
                results.add(l);
            }
        }
        return results;
    }


    public List<Link> getFinalOutputActivationLinks() {
        ArrayList<Link> results = new ArrayList<>();
        for (Link l : neuronOutputs) {
            if (l.output.isFinalActivation()) {
                results.add(l);
            }
        }
        return results;
    }


    public Collection<Activation> getConflicts() {
        if(conflicts != null) {
            return conflicts;
        }

        long v = doc.visitedCounter++;
        markPredecessor(v);
        conflicts = new ArrayList<>();
        for(Link l: neuronInputs.values()) {
            if (l.synapse.isNegative() && l.synapse.key.isRecurrent) {
                l.input.collectIncomingConflicts(conflicts, v);
            }
        }
        collectOutgoingConflicts(conflicts, v);
        return conflicts;
    }


    private void collectIncomingConflicts(List<Activation> conflicts, long v) {
        if(markedPredecessor == v) return;

        if (getINeuron().type != INeuron.Type.INHIBITORY) {
            conflicts.add(this);
        } else {
            for (Link l : neuronInputs.values()) {
                if (!l.synapse.isNegative() && !l.synapse.key.isRecurrent) {
                    l.input.collectIncomingConflicts(conflicts, v);
                }
            }
        }
    }


    private void collectOutgoingConflicts(List<Activation> conflicts, long v) {
        if(markedPredecessor == v) return;

        for(Link l: neuronOutputs) {
            if (l.output.getINeuron().type != INeuron.Type.INHIBITORY) {
                if (l.synapse.isNegative() && l.synapse.key.isRecurrent) {
                    conflicts.add(l.output);
                }
            } else if (!l.synapse.isNegative() && !l.synapse.key.isRecurrent) {
                l.output.collectOutgoingConflicts(conflicts, v);
            }
        }
    }


    public void adjustSelectedNeuronInputs(Decision d) {
        for(Link l: neuronOutputs) {
            if(d == SELECTED) {
                l.output.selectedNeuronInputs.add(l);
            } else {
                l.output.selectedNeuronInputs.remove(l);
            }
        }
    }


    public boolean checkSelfReferencing(boolean onlySelected, int depth, long v) {
        if (markedPredecessor == v) {
            return true;
        }

        if (depth > MAX_SELF_REFERENCING_DEPTH) {
            return false;
        }

        for (Link l: onlySelected ? selectedNeuronInputs : neuronInputs.values()) {
            if(!l.synapse.key.isRecurrent) {
                if (l.input.checkSelfReferencing(onlySelected, depth + 1, v)) {
                    return true;
                }
            }
        }

        return false;
    }


    public void setDecision(Decision newDecision, long v) {
        if(inputDecision != Decision.UNKNOWN && newDecision != inputDecision) return;

        if (newDecision == Decision.UNKNOWN && v != visitedState) return;

        if(decision == Decision.SELECTED != (newDecision == Decision.SELECTED)) {
            adjustSelectedNeuronInputs(newDecision);
        }

        decision = newDecision;
        visitedState = v;
    }


    public boolean isFinalActivation() {
        return getFinalState().value > 0.0;
    }


    public State getFinalState() {
        return finalRounds.getLast();
    }


    public Integer getSequence() {
        if (sequence != null) return sequence;

        sequence = 0;
        neuronInputs.values().stream().filter(sa -> !sa.synapse.key.isRecurrent).forEach(sa -> sequence = Math.max(sequence, sa.input.getSequence() + 1));
        return sequence;
    }


    public void markDirty(long v) {
        markedDirty = Math.max(markedDirty, v);
    }


    public void markPredecessor(long v) {
        markedPredecessor = v;

        for(Link l: neuronInputs.values()) {
            if(!l.synapse.isNegative() && !l.synapse.key.isRecurrent) {
                l.input.markPredecessor(v);
            }
        }
    }


    /**
     * Since Aika is a recurrent neural network, it is necessary to compute several rounds of activation values. The
     * computation stops if no further changes occur to the state. Only the recurrent synapses depend on the previous
     * round.
     *
     */
    public static class Rounds {
        private boolean[] isQueued = new boolean[3];

        public TreeMap<Integer, State> rounds = new TreeMap<>();


        public Rounds() {
            rounds.put(0, State.ZERO);
        }


        public boolean set(int r, State s) {
            State lr = get(r - 1);
            if(lr != null && lr.equalsWithWeights(s)) {
                State or = rounds.get(r);
                if(or != null) {
                    rounds.remove(r);
                    return !or.equalsWithWeights(s);
                }
                return false;
            } else {
                State or = rounds.put(r, s);

                for(Iterator<Map.Entry<Integer, State>> it = rounds.tailMap(r + 1).entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Integer, State> me = it.next();
                    if(me.getValue().equalsWithWeights(s)) it.remove();
                }
                return or == null || !or.equalsWithWeights(s);
            }
        }


        public State get(int r) {
            Map.Entry<Integer, State> me = rounds.floorEntry(r);
            return me != null ? me.getValue() : null;
        }

        public Rounds copy() {
            Rounds nr = new Rounds();
            nr.rounds.putAll(rounds);
            return nr;
        }

        public Integer getLastRound() {
            return !rounds.isEmpty() ? rounds.lastKey() : null;
        }

        public State getLast() {
            return !rounds.isEmpty() ? rounds.lastEntry().getValue() : State.ZERO;
        }

        public void setQueued(int r, boolean v) {
            if(r >= isQueued.length) {
                isQueued = Arrays.copyOf(isQueued, isQueued.length * 2);
            }
            isQueued[r] = v;
        }

        public boolean isQueued(int r) {
            return r < isQueued.length ? isQueued[r] : false;
        }


        public void reset() {
            rounds.clear();
            rounds.put(0, State.ZERO);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder();
            rounds.forEach((r, s) -> sb.append(r + ":" + s.value + " "));
            return sb.toString();
        }


        public boolean compare(Rounds r) {
            if(rounds.size() != r.rounds.size()) {
                return false;
            }
            for(Map.Entry<Integer, State> me: rounds.entrySet()) {
                State sa = me.getValue();
                State sb = r.rounds.get(me.getKey());
                if(sb == null || Math.abs(sa.value - sb.value) > 0.0000001) {
                    return false;
                }
            }

            return true;
        }


        public boolean isActive() {
            return rounds.size() <= 1 && getLast().value > 0.0;
        }
    }


    /**
     * A <code>State</code> object contains the activation value of an activation object that belongs to a neuron.
     * It furthermore contains a weight that is used to create the interpretations during the search for the best
     * interpretation.
     */
    public static class State {
        public static final int DIR = 0;
        public static final int REC = 1;

        public final double value;
        public final double net;

        public final int fired;
        public final double weight;

        public static final State ZERO = new State(0.0, 0.0, -1, 0.0);

        public State(double value, double net, int fired, double weight) {
            assert !Double.isNaN(value);
            this.value = value;
            this.net = net;
            this.fired = fired;
            this.weight = weight;
        }


        public boolean equals(State s) {
            return Math.abs(value - s.value) <= INeuron.WEIGHT_TOLERANCE;
        }

        public boolean equalsWithWeights(State s) {
            return equals(s) && Math.abs(weight - s.weight) <= INeuron.WEIGHT_TOLERANCE;
        }

        public String toString() {
            return "V:" + Utils.round(value) + " " + weight;
        }
    }


    public String toString() {
        return range + " - " + node + " -" +
                " UB:" + Utils.round(upperBound) +
                (inputValue != null ? " IV:" + Utils.round(inputValue) : "") +
                (targetValue != null ? " TV:" + Utils.round(targetValue) : "") +
                " V:" + Utils.round(rounds.getLast().value) +
                " FV:" + Utils.round(finalRounds.getLast().value);
    }



    public String toString(boolean finalOnly, boolean withTextSnippet, boolean withLogic) {
        StringBuilder sb = new StringBuilder();
        sb.append(id + " - ");

        sb.append((finalOnly ? finalDecision : decision) + " - ");

        sb.append(range);

        if(withTextSnippet) {
            sb.append(" \"");
            if(node.neuron.get().outputText != null) {
                sb.append(Utils.collapseText(node.neuron.get().outputText, 7));
            } else {
                sb.append(Utils.collapseText(doc.getText(range), 7));
            }
            sb.append("\"");
        }

        sb.append(" (");
        boolean first = true;
        for(Link l: neuronInputs.values()) {
            if(l.synapse.key.identity) {
                if(!first) {
                    sb.append(", ");
                }

                sb.append(l.input.id);

                first = false;
            }
        }
        sb.append(") - ");

        sb.append(withLogic ? node.toString() : node.getNeuronLabel());

        sb.append(" - UB:");
        sb.append(Utils.round(upperBound));
        sb.append(" MW:");
        sb.append(Utils.round(maxWeight));

        sb.append(" - ");
        if(finalOnly) {
            if (isFinalActivation()) {
                State fs = getFinalState();
                sb.append(fs);
            }
        } else {
            for (Map.Entry<Integer, State> me : rounds.rounds.entrySet()) {
                State s = me.getValue();
                sb.append("[R: " + me.getKey() + " " + s + "]");
            }
        }

        if (inputValue != null) {
            sb.append(" - IV:" + Utils.round(inputValue));
        }

        if (targetValue != null) {
            sb.append(" - TV:" + Utils.round(targetValue));
        }

        return sb.toString();
    }


    public String linksToString() {
        StringBuilder sb = new StringBuilder();
        for(Link l: neuronInputs.values()) {
            sb.append("  " + l.input.getLabel() + "  W:" + l.synapse.weight + "\n");
        }

        return sb.toString();
    }


    public enum Mode {OLD, NEW}

    public void saveOldState(Map<Activation, StateChange> changes, long v) {
        StateChange sc = currentStateChange;
        if (sc == null || currentStateV != v) {
            sc = new StateChange();
            sc.oldRounds = rounds.copy();
            currentStateChange = sc;
            currentStateV = v;
            if (changes != null) {
                changes.put(sc.getActivation(), sc);
            }
        }
    }

    public void saveNewState() {
        StateChange sc = currentStateChange;

        sc.newRounds = rounds.copy();
        sc.newState = decision;
    }


    /**
     * The {@code StateChange} class is used to store the state change of an activation that occurs in each node of
     * the binary search tree. When a candidate refinement is selected during the search, then the activation values of
     * all affected activation objects are adjusted. The changes to the activation values are also propagated through
     * the network. The old state needs to be stored here in order for the search to be able to restore the old network
     * state before following the alternative search branch.
     */
    public class StateChange {
        public Rounds oldRounds;
        public Rounds newRounds;
        public Decision newState;

        public void restoreState(Mode m) {
            rounds = (m == Mode.OLD ? oldRounds : newRounds).copy();
        }

        public Activation getActivation() {
            return Activation.this;
        }
    }


    /**
     * The {@code SynapseActivation} mirror the synapse link in the network of activations.
     */
    public static class Link {
        public final Synapse synapse;
        public final Activation input;
        public final Activation output;

        public static Comparator<Link> INPUT_COMP = (sa1, sa2) -> {
            int r = Synapse.INPUT_SYNAPSE_COMP.compare(sa1.synapse, sa2.synapse);
            if (r != 0) return r;
            return Integer.compare(sa1.input.id, sa2.input.id);
        };

        public static Comparator<Link> OUTPUT_COMP = (sa1, sa2) -> {
            int r = Synapse.OUTPUT_SYNAPSE_COMP.compare(sa1.synapse, sa2.synapse);
            if (r != 0) return r;
            return Integer.compare(sa1.output.id, sa2.output.id);
        };


        public Link(Synapse s, Activation input, Activation output) {
            this.synapse = s;
            this.input = input;
            this.output = output;
        }

        public String toString() {
            return synapse + ": " + input + " --> " + output;
        }
    }

    public static class Builder {
        public Range range;
        public double value = 1.0;
        public Double targetValue;
        public int fired;


        public Builder setRange(int begin, int end) {
            this.range = new Range(begin, end);
            return this;
        }

        public Builder setRange(Range range) {
            this.range = range;
            return this;
        }

        public Builder setValue(double value) {
            this.value = value;
            return this;
        }

        public Builder setTargetValue(Double targetValue) {
            this.targetValue = targetValue;
            return this;
        }

        public Builder setFired(int fired) {
            this.fired = fired;
            return this;
        }
    }
}

