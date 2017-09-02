/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aika;


import org.aika.corpus.Document;
import org.aika.corpus.SearchNode.StateChange;
import org.aika.corpus.InterprNode;
import org.aika.corpus.InterprNode.Relation;
import org.aika.corpus.Range;
import org.aika.corpus.Range.Operator;
import org.aika.lattice.Node;
import org.aika.lattice.Node.ThreadState;
import org.aika.lattice.OrNode;
import org.aika.neuron.Neuron;
import org.aika.neuron.Neuron.NormWeight;
import org.aika.neuron.Synapse;

import java.util.*;
import java.util.stream.Stream;

import static org.aika.Activation.SynapseActivation.INPUT_COMP;
import static org.aika.Activation.SynapseActivation.OUTPUT_COMP;
import static org.aika.corpus.Range.Operator.*;

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
public class Activation<T extends Node> implements Comparable<Activation> {

    public int id;

    public final Key<T> key;

    public boolean isRemoved;
    public int removedId;
    public long visitedNeuronTrain = -1;

    public static int removedIdCounter = 1;
    public static long visitedCounter = 1;

    public double upperBound;
    public double lowerBound;

    public Rounds rounds = new Rounds();

    public State finalState;

    public long currentStateV = -1;
    public StateChange currentStateChange;

    public boolean isTrainingAct;

    public double initialErrorSignal;
    public double errorSignal;

    public TreeMap<Key, Activation> inputs = new TreeMap<>();
    public TreeMap<Key, Activation> outputs = new TreeMap<>();

    static final SynapseActivation[] EMPTY_SYN_ACTS = new SynapseActivation[0];
    public SynapseActivation[] neuronInputs = EMPTY_SYN_ACTS;
    public SynapseActivation[] neuronOutputs = EMPTY_SYN_ACTS;


    public boolean ubQueued = false;
    public boolean isQueued = false;
    public long queueId;

    public boolean isInput;


    public Activation(int id, Key key) {
        this.id = id;
        this.key = key;
    }


    public Activation(int id, Node n, Range pos, Integer rid, InterprNode o) {
        this.id = id;
        key = new Key(n, pos, rid, o);
    }


    public void link(Collection<Activation> inputActs) {
        for(Activation iAct: inputActs) {
            inputs.put(iAct.key, iAct);
            iAct.outputs.put(key, this);
        }
    }


    public void unlink(Collection<Activation> inputActs) {
        for (Activation iAct : inputActs) {
            inputs.remove(iAct.key);
            iAct.outputs.remove(key);
        }
    }


    public void unlink() {
        for (Activation act : inputs.values()) {
            act.outputs.remove(key);
        }
        for (Activation act : outputs.values()) {
            act.inputs.remove(key);
        }
    }


    public static <T extends Node> Activation<T> get(Document doc, T n, Integer rid, Range r, Operator begin, Operator end, InterprNode o, InterprNode.Relation or) {
        return select(doc, n, rid, r, begin, end, o, or)
                .findFirst()
                .orElse(null);
    }


    public static Activation get(Document doc, Node n, Key ak) {
        return get(doc, n, ak.rid, ak.r, Operator.EQUALS, Operator.EQUALS, ak.o, InterprNode.Relation.EQUALS);
    }


    public static Activation getNextSignal(Node n, Document doc, int from, Integer rid, InterprNode o, boolean dir, boolean inv) {
        ThreadState th = n.getThreadState(doc.threadId, false);
        if(th == null) return null;

        Key bk = new Key(null, new Range(from, dir ? Integer.MIN_VALUE : Integer.MAX_VALUE).invert(inv), rid, o);
        NavigableMap<Key, Activation> tmp = (inv ? th.activationsEnd : th.activations);
        tmp = dir ? tmp.descendingMap() : tmp;
        for(Activation act: tmp.tailMap(bk, false).values()) {
            if(act.filter(n, rid, null, null, null, o, InterprNode.Relation.CONTAINED_IN)) {
                return act;
            }
        }
        return null;
    }


    public static Stream<Activation> select(Document doc, Integer rid, Range r, Operator begin, Operator end, InterprNode o, Relation or) {
        Stream<Activation> results;
        if(rid != null) {
            Key bk = new Key(Node.MIN_NODE, Range.MIN, rid, InterprNode.MIN);
            Key ek = new Key(Node.MAX_NODE, Range.MAX, rid, InterprNode.MAX);

            results = doc.activationsByRid.subMap(bk, true, ek, true)
                    .values()
                    .stream();
        } else {
            results = doc.activatedNodes
                    .stream()
                    .flatMap(node -> getActivationsStream(node, doc));
        }

        return results.filter(act -> act.filter(null, rid, r, begin, end, o, or));
    }


    public static <T extends Node> Stream<Activation<T>> select(Document doc, T n, Integer rid, Range r, Operator begin, Operator end, InterprNode o, Relation or) {
        Stream<Activation<T>> results;
        ThreadState<T> th = n.getThreadState(doc.threadId, false);
        if(th == null) return Stream.empty();
        return select(th, n, rid, r, begin, end, o, or);
    }


    public static <T extends Node> Stream<Activation<T>> select(ThreadState<T> th, T n, Integer rid, Range r, Operator begin, Operator end, InterprNode o, Relation or) {
        Stream<Activation<T>> results;
        int s = th.activations.size();

        if(s == 0) return Stream.empty();
        else if(s == 1) {
            results = th.activations
                    .values()
                    .stream();
        } else if(rid != null) {
            Key bk = new Key(n, Range.MIN, rid, InterprNode.MIN);
            Key ek = new Key(n, Range.MAX, rid, InterprNode.MAX);

            if(th.activationsRid != null) {
                results = th.activationsRid.subMap(bk, true, ek, true)
                        .values()
                        .stream();
            } else return Stream.empty();
        } else {
            if(begin == null && end == null) {
                results = th.activations.values()
                        .stream();
            } else {
                return getActivationsByRange(th, n, rid, r, begin, end, o, or);
            }
        }

        return results.filter(act -> act.filter(n, rid, r, begin, end, o, or));
    }


    public static <T extends Node> Stream<Activation<T>> getActivationsByRange(ThreadState<T> th, T n, Integer rid, Range r, Operator begin, Operator end, InterprNode o, InterprNode.Relation or) {
        Stream<Activation<T>> s;
        if((begin == GREATER_THAN || begin == EQUALS || end == FIRST) && r.begin != null) {
            int er = (end == Operator.LESS_THAN || end == Operator.EQUALS || end == FIRST) && r.end != null ? r.end : Integer.MAX_VALUE;
            s = th.activations.subMap(
                    new Activation.Key(n, new Range(r.begin, null), null, InterprNode.MIN),
                    true,
                    new Activation.Key(n, new Range(er, Integer.MAX_VALUE), Integer.MAX_VALUE, InterprNode.MAX),
                    true
            )
                    .values()
                    .stream()
                    .filter(act -> act.filter(n, rid, r, begin, end, o, or));
        } else if((begin == Operator.LESS_THAN || begin == Operator.EQUALS) && r.begin != null) {
            s = th.activations.descendingMap().subMap(
                    new Activation.Key(n, new Range(r.begin, Integer.MAX_VALUE), null, InterprNode.MAX),
                    true,
                    new Activation.Key(n, new Range(null, null), null, InterprNode.MIN),
                    true
            )
                    .values()
                    .stream()
                    .filter(act -> act.filter(n, rid, r, begin, end, o, or));
        }  else if(end == LAST) {
            s = th.activationsEnd.tailMap(
                    new Activation.Key(n, new Range(null, r.begin), null, InterprNode.MIN),
                    true
            )
                    .values()
                    .stream()
                    .filter(act -> act.filter(n, rid, r, begin, end, o, or));
        } else if(begin == LAST || begin == FIRST || end == FIRST) {
            // TODO
            throw new RuntimeException("Not implemented yet!");
        } else {
            s = th.activations.values()
                    .stream()
                    .filter(act -> act.filter(n, rid, r, begin, end, o, or));
        }

        return s;
    }


    private static <T extends Node> Stream<Activation<T>> getActivationsStream(T n, Document doc) {
        ThreadState<T> th = n.getThreadState(doc.threadId, false);
        return th == null ? Stream.empty() : th.activations.values().stream();
    }


    public <T extends Node> boolean filter(T n, Integer rid, Range r, Operator begin, Operator end, InterprNode o, InterprNode.Relation or) {
        return (n == null || key.n == n) &&
                (rid == null || (key.rid != null && key.rid.intValue() == rid.intValue())) &&
                (r == null || ((begin == null || begin.compare(key.r.begin, key.r.end, r.begin, r.end)) && (end == null || end.compare(key.r.end, key.r.begin, r.end, r.begin)))) &&
                (o == null || or.compare(key.o, o));
    }


    public String toString(Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("<ACT ");
        sb.append(",(");
        sb.append(key.r);
        sb.append("),");
        sb.append(doc.getContent().substring(Math.max(0, key.r.begin - 3), Math.min(doc.length(), key.r.end + 3)));
        sb.append(",");
        sb.append(key.n);
        sb.append(">");
        return sb.toString();
    }


    @Override
    public int compareTo(Activation act) {
        return key.compareTo(act.key);
    }


    public static int compare(Activation a, Activation b) {
        if(a == b) return 0;
        if(a == null && b != null) return -1;
        if(a != null && b == null) return 1;
        return a.compareTo(b);
    }


    public static final class Key<T extends Node> implements Comparable<Key> {
        public final T n;
        public final Range r;
        public final Integer rid;
        public final InterprNode o;

        private int refCount = 0;


        public Key(T n, Range r, Integer rid, InterprNode o) {
            this.n = n;
            this.r = r;
            this.rid = rid;
            this.o = o;
            countRef();
            if(o != null) {
                o.countRef();
            }
        }


        public void countRef() {
            refCount++;
        }


        public void releaseRef() {
            assert refCount > 0;
            refCount--;
            if(refCount == 0) {
                o.releaseRef();
            }
        }


        @Override
        public int compareTo(Key k) {
            int x = n.compareTo(k.n);
            if(x != 0) return x;
            x = Range.compare(r, k.r, false);
            if(x != 0) return x;
            x = Utils.compareInteger(rid, k.rid);
            if(x != 0) return x;
            return o.compareTo(k.o);
        }


        public String toString() {
            return (n != null ? n.toSimpleString() : "") + r + " " + rid + " " + o;
        }
    }


    /**
     * A <code>State</code> object contains the activation value of an activation object that belongs to a neuron.
     * It furthermore contains a weight that is used to evaluate the interpretations during the search for the best
     * interpretation.
     */
    public static class State {
        public final double value;
        public final double ub;
        public final double lb;

        public final int fired;
        public final NormWeight weight;
        public final NormWeight weightUB;

        public static final State ZERO = new State(0.0, 0.0, 0.0, -1, NormWeight.ZERO_WEIGHT, NormWeight.ZERO_WEIGHT);

        public State(double value, double ub, double lb, int fired, NormWeight weight, NormWeight weightUB) {
            assert lb <= value && value <= ub;
            assert weight.w <= weightUB.w && weightUB.n <= weight.n;
            this.value = value;
            this.ub = ub;
            this.lb = lb;
            this.fired = fired;
            this.weight = weight;
            this.weightUB = weightUB;
        }

        public NormWeight getWeight(int t) {
            return t == 0 ? weight : weightUB;
        }

        public boolean equals(State s) {
            return Math.abs(value - s.value) <= Neuron.WEIGHT_TOLERANCE || Math.abs(ub - s.ub) <= Neuron.WEIGHT_TOLERANCE || Math.abs(lb - s.lb) <= Neuron.WEIGHT_TOLERANCE;
        }

        public boolean equalsWithWeights(State s) {
            return equals(s) && weight.equals(s.weight);
        }

        public String toString() {
            return "V:" + value;
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
            return me != null ? me.getValue() : State.ZERO;
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
            return !rounds.isEmpty() ? rounds.lastEntry().getValue() : null;
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
    }


    public void addSynapseActivation(int dir, SynapseActivation sa) {
        if(dir == 0) {
            if(Utils.contains(neuronOutputs, sa, OUTPUT_COMP)) return;
            neuronOutputs = Utils.addToArray(neuronOutputs, sa);
        } else {
            if(Utils.contains(neuronInputs, sa, INPUT_COMP)) return;
            neuronInputs = Utils.addToArray(neuronInputs, sa);
        }
    }


    public void removeSynapseActivation(int dir, SynapseActivation sa) {
        if(dir == 0) {
            neuronOutputs = Utils.removeToArray(neuronOutputs, sa);
        } else {
            neuronInputs = Utils.removeToArray(neuronInputs, sa);
        };
    }


    /**
     * The {@code SynapseActivation} mirror the synapse link in the network of activations.
     */
    public static class SynapseActivation {
        public final Synapse s;
        public final Activation<OrNode> input;
        public final Activation<OrNode> output;

        public static Comparator<SynapseActivation> INPUT_COMP = new Comparator<SynapseActivation>() {
            @Override
            public int compare(SynapseActivation sa1, SynapseActivation sa2) {
                int r = Synapse.INPUT_SYNAPSE_COMP.compare(sa1.s, sa2.s);
                if(r != 0) return r;
                return sa1.input.compareTo(sa2.input);
            }
        };

        public static Comparator<SynapseActivation> OUTPUT_COMP = new Comparator<SynapseActivation>() {
            @Override
            public int compare(SynapseActivation sa1, SynapseActivation sa2) {
                int r = Synapse.OUTPUT_SYNAPSE_COMP.compare(sa1.s, sa2.s);
                if(r != 0) return r;
                return sa1.output.compareTo(sa2.output);
            }
        };


        public SynapseActivation(Synapse s, Activation input, Activation output) {
            this.s = s;
            this.input = input;
            this.output = output;
        }
    }

}
