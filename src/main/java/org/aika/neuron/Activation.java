package org.aika.neuron;

import org.aika.Utils;
import org.aika.corpus.Document;
import org.aika.corpus.InterprNode;
import org.aika.corpus.Range;
import org.aika.corpus.SearchNode;
import org.aika.lattice.Node;
import org.aika.lattice.NodeActivation;
import org.aika.lattice.OrNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

import static org.aika.corpus.Range.Operator.EQUALS;
import static org.aika.corpus.Range.Operator.GREATER_THAN_EQUAL;
import static org.aika.neuron.Activation.SynapseActivation.INPUT_COMP;
import static org.aika.neuron.Activation.SynapseActivation.OUTPUT_COMP;


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
public final class Activation extends NodeActivation<OrNode> {
    private static final Logger log = LoggerFactory.getLogger(Activation.class);

    public TreeSet<SynapseActivation> neuronInputs = new TreeSet<>(INPUT_COMP);
    public TreeSet<SynapseActivation> neuronOutputs = new TreeSet<>(OUTPUT_COMP);

    public Integer sequence;

    public double upperBound;
    public double lowerBound;

    public Rounds rounds = new Rounds();
    public double maxActValue = 0.0;

    public boolean ubQueued = false;
    public boolean isQueued = false;
    public long queueId;

    public long currentStateV;
    public StateChange currentStateChange;

    public double errorSignal;
    public Double targetValue;
    public Double inputValue;


    public Activation(int id, Document doc, Key key) {
        super(id, doc, key);
    }


    public void addSynapseActivation(int dir, SynapseActivation sa) {
        if(dir == 0) {
            neuronOutputs.add(sa);
        } else {
            neuronInputs.add(sa);
        }
    }


    public void removeSynapseActivation(int dir, SynapseActivation sa) {
        if(dir == 0) {
            neuronOutputs.remove(sa);
        } else {
            neuronInputs.remove(sa);
        };
    }


    public List<SynapseActivation> getFinalInputActivations() {
        ArrayList<SynapseActivation> results = new ArrayList<>();
        for (SynapseActivation inputAct : neuronInputs) {
            if (inputAct.input.isFinalActivation()) {
                results.add(inputAct);
            }
        }
        return results;
    }


    public List<SynapseActivation> getFinalOutputActivations() {
        ArrayList<SynapseActivation> results = new ArrayList<>();
        for (SynapseActivation inputAct : neuronOutputs) {
            if (inputAct.output.isFinalActivation()) {
                results.add(inputAct);
            }
        }
        return results;
    }


    public boolean isFinalActivation() {
        return getFinalState().value > 0.0 || (targetValue != null && targetValue > 0.0);
    }


    public void updateErrorSignal() {
        if(errorSignal != 0.0) {
            doc.errorSignalActivations.add(this);
            for (SynapseActivation sa : neuronInputs) {
                doc.bQueue.add(sa.input);
            }
        }
    }


    public State getFinalState() {
        return rounds.getLast();
    }


    public static Activation get(Document doc, INeuron n, Integer rid, Range r, Range.Relation rr, InterprNode o, InterprNode.Relation or) {
        Stream<Activation> s = select(doc, n, rid, r, rr, o, or);
        return s.findFirst()
                .orElse(null);
    }


    public static Activation get(Document doc, INeuron n, Key ak) {
        return get(doc, n, ak.rid, ak.range, Range.Relation.EQUALS, ak.interpretation, InterprNode.Relation.EQUALS);
    }


    public static Stream<Activation> select(Document doc, INeuron n, Integer rid, Range r, Range.Relation rr, InterprNode o, InterprNode.Relation or) {
        INeuron.ThreadState th = n.getThreadState(doc.threadId, false);
        if(th == null) return Stream.empty();
        return select(th, n, rid, r, rr, o, or);
    }


    public static Stream<Activation> select(INeuron.ThreadState th, INeuron n, Integer rid, Range r, Range.Relation rr, InterprNode o, InterprNode.Relation or) {
        Stream<Activation> results;
        int s = th.activations.size();

        Node node = n.node.get();
        if(s == 0) return Stream.empty();
        else if(s == 1) {
            results = th.activations
                    .values()
                    .stream();
        } else if(rid != null) {
            Key bk = new Key(node, Range.MIN, rid, InterprNode.MIN);
            Key ek = new Key(node, Range.MAX, rid, InterprNode.MAX);

            if(th.activationsRid != null) {
                results = th.activationsRid.subMap(bk, true, ek, true)
                        .values()
                        .stream();
            } else return Stream.empty();
        } else {
            if(rr == null) {
                results = th.activations.values()
                        .stream();
            } else {
                return getActivationsByRange(th, n, rid, r, rr, o, or);
            }
        }

        return results.filter(act -> act.filter(node, rid, r, rr, o, or));
    }


    public static Stream<Activation> getActivationsByRange(INeuron.ThreadState th, INeuron n, Integer rid, Range r, Range.Relation rr, InterprNode o, InterprNode.Relation or) {
        Collection<Activation> s;
        Node node = n.node.get();
        if((rr.beginToBegin == GREATER_THAN_EQUAL || rr.beginToBegin == EQUALS) && r.begin != Integer.MIN_VALUE && r.begin <= r.end) {
            int er = (rr.endToEnd == Range.Operator.LESS_THAN_EQUAL || rr.endToEnd == Range.Operator.EQUALS) && r.end != Integer.MAX_VALUE ? r.end : Integer.MAX_VALUE;
            s = th.activations.subMap(
                    new NodeActivation.Key(node, new Range(r.begin, Integer.MIN_VALUE), null, InterprNode.MIN),
                    true,
                    new NodeActivation.Key(node, new Range(er, Integer.MAX_VALUE), Integer.MAX_VALUE, InterprNode.MAX),
                    true
            )
                    .values();
        } else if((rr.beginToBegin == Range.Operator.LESS_THAN_EQUAL || rr.beginToBegin == Range.Operator.EQUALS) && r.begin != Integer.MIN_VALUE && r.begin <= r.end) {
            s = th.activations.descendingMap().subMap(
                    new NodeActivation.Key(node, new Range(r.begin, Integer.MAX_VALUE), null, InterprNode.MAX),
                    true,
                    new NodeActivation.Key(node, new Range(Integer.MIN_VALUE, Integer.MIN_VALUE), null, InterprNode.MIN),
                    true
            )
                    .values();
        } else {
            s = th.activations.values();
        }

        return s.stream().filter(act -> act.filter(node, rid, r, rr, o, or));
    }


    private static Stream<Activation> getActivationsStream(INeuron n, Document doc) {
        INeuron.ThreadState th = n.getThreadState(doc.threadId, false);
        return th == null ? Stream.empty() : th.activations.values().stream();
    }


    public <T extends Node> boolean filter(T n, Integer rid, Range r, Range.Relation rr, InterprNode o, InterprNode.Relation or) {
        return (n == null || key.node == n) &&
                (rid == null || (key.rid != null && key.rid.intValue() == rid.intValue())) &&
                (r == null || rr == null || rr.compare(key.range, r)) &&
                (o == null || or.compare(key.interpretation, o));
    }


    public Integer getSequence() {
        if(sequence != null) return sequence;

        sequence = 0;
        neuronInputs.stream().filter(sa -> !sa.synapse.key.isRecurrent).forEach(sa -> sequence = Math.max(sequence, sa.input.getSequence() + 1));
        return sequence;
    }


    /**
     * The {@code SynapseActivation} mirror the synapse link in the network of activations.
     */
    public static class SynapseActivation {
        public final Synapse synapse;
        public final Activation input;
        public final Activation output;

        public static Comparator<SynapseActivation> INPUT_COMP = (sa1, sa2) -> {
            int r = Synapse.INPUT_SYNAPSE_COMP.compare(sa1.synapse, sa2.synapse);
            if (r != 0) return r;
            return sa1.input.compareTo(sa2.input);
        };

        public static Comparator<SynapseActivation> OUTPUT_COMP = (sa1, sa2) -> {
            int r = Synapse.OUTPUT_SYNAPSE_COMP.compare(sa1.synapse, sa2.synapse);
            if (r != 0) return r;
            return sa1.output.compareTo(sa2.output);
        };


        public SynapseActivation(Synapse s, Activation input, Activation output) {
            this.synapse = s;
            this.input = input;
            this.output = output;
        }
    }


    /**
     * Since Aika is a recurrent neural network, it is necessary to compute several rounds of activation values. The
     * computation stops if no further changes occur to the state. Only the recurrent synapses depend on the previous
     * round.
     *
     */
    public static class Rounds {
        public long modified;

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
                    checkIfNonMonotone(or, s);

                    rounds.remove(r);
                    return !or.equalsWithWeights(s);
                }
                return false;
            } else {
                State or = rounds.put(r, s);

                checkIfNonMonotone(or, s);

                for(Iterator<Map.Entry<Integer, State>> it = rounds.tailMap(r + 1).entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Integer, State> me = it.next();
                    if(me.getValue().equalsWithWeights(s)) it.remove();
                }
                return or == null || !or.equalsWithWeights(s);
            }
        }

        public void checkIfNonMonotone(State os, State s) {
            if(os != null && os.value > s.value) {
                log.error("Error: Non monotone activation value occurred.");

                throw new RuntimeException("Non monotone activation value occurred.");
            }
        }

        public State get(int r) {
            Map.Entry<Integer, State> me = rounds.floorEntry(r);
            return me != null ? me.getValue() : null;
        }

        public Rounds copy() {
            Rounds nr = new Rounds();
            nr.modified = modified;
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
            modified = 0;
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
                if(sb == null || Utils.round(sa.value) != Utils.round(sb.value)) {
                    return false;
                }
            }

            return true;
        }
    }


    /**
     * A <code>State</code> object contains the activation value of an activation object that belongs to a neuron.
     * It furthermore contains a weight that is used to evaluate the interpretations during the search for the best
     * interpretation.
     */
    public static class State {
        public static final int DIR = 0;
        public static final int REC = 1;

        public final double value;

        public final int fired;
        public final INeuron.NormWeight weight;

        public static final State ZERO = new State(0.0, -1, INeuron.NormWeight.ZERO_WEIGHT);

        public State(double value, int fired, INeuron.NormWeight weight) {
            assert !Double.isNaN(value);
            this.value = value;
            this.fired = fired;
            this.weight = weight;
        }


        public boolean equals(State s) {
            return Math.abs(value - s.value) <= INeuron.WEIGHT_TOLERANCE;
        }

        public boolean equalsWithWeights(State s) {
            return equals(s) && weight.equals(s.weight);
        }

        public String toString() {
            return "VALUE:" + value;
        }
    }



    public String toString(SearchNode sn, boolean withWeights, boolean withTextSnipped, boolean withLogic) {
        StringBuilder sb = new StringBuilder();
        sb.append(id + " ");

        if(sn != null) {
            sb.append(key.interpretation.state + " ");
            sb.append(sequence + " ");
        }

        sb.append(key.range);

        if(withTextSnipped) {
            sb.append(" ");
            if(key.node.neuron.get().outputText != null) {
                sb.append(collapseText(key.node.neuron.get().outputText));
            } else {
                sb.append(collapseText(doc.getText(key.range)));
            }
        }
        sb.append(" - ");

        sb.append(key.interpretation);
        sb.append(" - ");

        sb.append(withLogic ? key.node.toString() : key.node.getNeuronLabel());

        sb.append(" - Rid:");
        sb.append(key.rid);

        sb.append(" - UB:");
        sb.append(Utils.round(upperBound));
        if (withWeights) {
            sb.append(" - ");
            for(Map.Entry<Integer, State> me: rounds.rounds.entrySet()) {
                State s = me.getValue();
                sb.append("[R:" + me.getKey());
                sb.append(" VALUE:" + Utils.round(s.value));
                sb.append(" W:" + Utils.round(s.weight.w));
                sb.append(" N:" + Utils.round(s.weight.n));
                sb.append("]");
            }

            if (isFinalActivation()) {
                State fs = getFinalState();
                sb.append(" - FV:" + Utils.round(fs.value));
                sb.append(" FW:" + Utils.round(fs.weight.w));
                sb.append(" FN:" + Utils.round(fs.weight.n));

                if(targetValue != null) {
                    sb.append(" - TV:" + Utils.round(targetValue));
                }
            }
        }

        return sb.toString();
    }


    private String collapseText(String txt) {
        if (txt.length() <= 10) {
            return txt;
        } else {
            return txt.substring(0, 5) + "..." + txt.substring(txt.length() - 5);
        }
    }


    public enum Mode {OLD, NEW}

    public void saveOldState(List<StateChange> changes, long v) {
        StateChange sc = currentStateChange;
        if (sc == null || currentStateV != v) {
            sc = new StateChange();
            sc.oldRounds = rounds.copy();
            currentStateChange = sc;
            currentStateV = v;
            if (changes != null) {
                changes.add(sc);
            }
        }
    }

    public void saveNewState() {
        StateChange sc = currentStateChange;

        sc.newRounds = rounds.copy();
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

        public void restoreState(Mode m) {
            rounds = (m == Mode.OLD ? oldRounds : newRounds).copy();
        }

        public Activation getActivation() {
            return Activation.this;
        }
    }

}

