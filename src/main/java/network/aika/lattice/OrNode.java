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
package network.aika.lattice;


import network.aika.*;
import network.aika.neuron.INeuron;
import network.aika.neuron.Neuron;
import network.aika.neuron.Synapse;
import network.aika.neuron.activation.Activation;
import network.aika.training.PatternDiscovery;
import network.aika.Document;
import network.aika.neuron.activation.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;


/**
 * While several neurons might share a the same input-node or and-node, there is always a always a one-to-one relation
 * between or-nodes and neurons. The only exceptions are the input neurons which have a one-to-one relation with the
 * input-node. The or-nodes form a disjunction of one or more input-nodes or and-nodes.
 *
 * @author Lukas Molzberger
 */
public class OrNode extends Node<OrNode, Activation> {

    private static final Logger log = LoggerFactory.getLogger(OrNode.class);

    public TreeSet<OrEntry> andParents = new TreeSet<>();

    public Neuron neuron = null;

    public OrNode() {}


    public OrNode(Model m) {
        super(m, -1); // Or-node activations always need to be processed first!
    }


    @Override
    public AndNode.RefValue extend(int threadId, Document doc, AndNode.Refinement ref) {
        throw new UnsupportedOperationException();
    }


    public void addInputActivation(OrEntry oe, NodeActivation inputAct) {
        Document doc = inputAct.doc;

        Integer begin = null;
        Integer end = null;

        for(int i = 0; i < oe.synapseIds.length; i++) {
            int synapseId = oe.synapseIds[i];

            neuron.get(inputAct.doc);
            Synapse s = neuron.getSynapseById(synapseId);
            if(s.key.rangeOutput.begin != Range.Mapping.NONE || s.key.rangeOutput.end != Range.Mapping.NONE) {
                Activation iAct = inputAct.getInputActivation(i);

                Integer b = s.key.rangeOutput.begin.map(iAct.range);
                if(b != null) begin = b;

                Integer e = s.key.rangeOutput.end.map(iAct.range);
                if(e != null) end = e;
            }
        }

        Range r = new Range(begin, end);

        if(neuron.get(doc).outputText != null) {
            begin = r.begin != Integer.MIN_VALUE ? r.begin : 0;
            end = r.end != Integer.MAX_VALUE ? r.end : begin + neuron.get(doc).outputText.length();
            r = new Range(begin, end);
        }

        if(r.begin == Integer.MIN_VALUE || r.end == Integer.MAX_VALUE) return;

        Activation act = lookupActivation(doc, r, oe, inputAct);

        if(act == null) {
            act = new Activation(doc.activationIdCounter++, doc, r, this);
            processActivation(act);
        } else {
            propagate(act);
        }

        Link ol = act.link(oe, inputAct);
        act.doc.linker.link(act, ol);
    }


    private Activation lookupActivation(Document doc, Range r, OrEntry oe, NodeActivation inputAct) {
        x: for(Activation act: neuron.get(doc).getThreadState(doc.threadId, true).activations.subMap(new INeuron.ActKey(r, Integer.MIN_VALUE), new INeuron.ActKey(r, Integer.MAX_VALUE)).values()) {
            for(Activation.Link l: act.neuronInputs.values()) {
                if (l.synapse.key.identity) {
                    Activation iActA = l.input;
                    Integer i = oe.revSynapseIds.get(l.synapse.id);
                    if(i != null) {
                        Activation iActB = inputAct.getInputActivation(i);
                        if (iActA != iActB) {
                            continue x;
                        }
                    }
                }
            }
            return act;
        }
        return null;
    }


    public void propagate(Activation act) {
        act.doc.ubQueue.add(act);
    }


    Activation processActivation(Activation act) {
        super.processActivation(act);
        neuron.get(act.doc).register(act);
        return act;
    }


    @Override
    public void cleanup() {

    }


    @Override
    public void apply(Activation act) {
        throw new UnsupportedOperationException();
    }


    @Override
    public void discover(Activation act, PatternDiscovery.Config config) {
        throw new UnsupportedOperationException();
    }


    public static void processCandidate(Node<?, ? extends NodeActivation<?>> parentNode, NodeActivation inputAct, boolean train) {
        Document doc = inputAct.doc;
        try {
            parentNode.lock.acquireReadLock();
            if (parentNode.orChildren != null) {
                for (OrEntry oe : parentNode.orChildren) {
                    oe.child.get(doc).addInputActivation(oe, inputAct);
                }
            }
        } finally {
            parentNode.lock.releaseReadLock();
        }
    }


    @Override
    public void reprocessInputs(Document doc) {
        for (OrEntry oe : andParents) {
            Node<?, NodeActivation<?>> pn = oe.parent.get();
            for (NodeActivation act : pn.getActivations(doc)) {
                act.repropagateV = markedCreated;
                act.node.propagate(act);
            }
        }
    }


    public void addInput(int[] synapseIds, int threadId, Node in, boolean andMode) {
        in.changeNumberOfNeuronRefs(threadId, provider.model.visitedCounter.addAndGet(1), 1);

        OrEntry oe = new OrEntry(synapseIds, in.provider, provider);
        in.addOrChild(oe);
        in.setModified();

        if(andMode) {
            lock.acquireWriteLock();
            setModified();
            andParents.add(oe);
            lock.releaseWriteLock();
        }
    }


    void remove(int threadId) {
        neuron.get().remove();

        super.remove();

        try {
            lock.acquireReadLock();
            removeParents(threadId);
        } finally {
            lock.releaseReadLock();
        }
    }


    public void removeParents(int threadId) {
        for (OrEntry oe : andParents) {
            Node pn = oe.parent.get();
            pn.changeNumberOfNeuronRefs(threadId, provider.model.visitedCounter.addAndGet(1), -1);
            pn.removeOrChild(oe);
            pn.setModified();
        }
        andParents.clear();
    }


    @Override
    public void changeNumberOfNeuronRefs(int threadId, long v, int d) {
        throw new UnsupportedOperationException();
    }


    public String logicToString() {
        StringBuilder sb = new StringBuilder();
        sb.append("OR[");
        boolean first = true;
        int i = 0;
        for(OrEntry oe : andParents) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(oe.parent.get().logicToString());
            if (i > 2) {
                sb.append(",...");
                break;
            }

            i++;
        }

        sb.append("]");
        return sb.toString();
    }


    @Override
    public void write(DataOutput out) throws IOException {
        out.writeBoolean(false);
        out.writeChar('O');
        super.write(out);

        out.writeInt(neuron.id);

        out.writeInt(andParents.size());
        for(OrEntry oe: andParents) {
            oe.write(out);
        }
    }


    @Override
    public void readFields(DataInput in, Model m) throws IOException {
        super.readFields(in, m);

        neuron = m.lookupNeuron(in.readInt());

        int s = in.readInt();
        for(int i = 0; i < s; i++) {
            andParents.add(OrEntry.read(in, m));
        }
    }


    public String getNeuronLabel() {
        String l = neuron.getLabel();
        return l != null ? l : "";
    }


    public static class OrEntry implements Comparable<OrEntry>, Writable {
        public int[] synapseIds;
        public TreeMap<Integer, Integer> revSynapseIds = new TreeMap<>();
        public Provider<? extends Node> parent;
        public Provider<OrNode> child;

        private OrEntry() {}

        public OrEntry(int[] synapseIds, Provider<? extends Node> parent, Provider<OrNode> child) {
            this.synapseIds = synapseIds;
            for(int ofs = 0; ofs < synapseIds.length; ofs++) {
                revSynapseIds.put(synapseIds[ofs], ofs);
            }

            this.parent = parent;
            this.child = child;
        }

        @Override
        public void write(DataOutput out) throws IOException {
            out.writeInt(synapseIds.length);
            for(int i = 0; i < synapseIds.length; i++) {
                Integer ofs = synapseIds[i];
                out.writeBoolean(ofs != null);
                out.writeInt(ofs);
            }
            out.writeInt(parent.id);
            out.writeInt(child.id);
        }

        public static OrEntry read(DataInput in, Model m)  throws IOException {
            OrEntry rv = new OrEntry();
            rv.readFields(in, m);
            return rv;
        }

        @Override
        public void readFields(DataInput in, Model m) throws IOException {
            int l = in.readInt();
            synapseIds = new int[l];
            for(int i = 0; i < l; i++) {
                if(in.readBoolean()) {
                    Integer ofs = in.readInt();
                    synapseIds[i] = ofs;
                    revSynapseIds.put(ofs, i);
                }
            }
            parent = m.lookupNodeProvider(in.readInt());
            child = m.lookupNodeProvider(in.readInt());
        }


        @Override
        public int compareTo(OrEntry oe) {
            int r = child.compareTo(oe.child);
            if(r != 0) return r;

            r = parent.compareTo(oe.parent);
            if(r != 0) return r;

            r = Integer.compare(synapseIds.length, oe.synapseIds.length);
            if(r != 0) return r;

            for(int i = 0; i < synapseIds.length; i++) {
                r = Integer.compare(synapseIds[i], oe.synapseIds[i]);
                if(r != 0) return r;
            }
            return 0;
        }
    }


    public static class OrActivation extends NodeActivation<OrNode> {
        public Map<Integer, Link> inputs = new TreeMap<>();

        public OrActivation(int id, Document doc, OrNode node) {
            super(id, doc, node);
        }

        @Override
        public Activation getInputActivation(int i) {
            throw new UnsupportedOperationException();
        }

        public Link link(OrEntry oe, NodeActivation<?> input) {
            Link l = new Link(oe, input, this);
            inputs.put(input.id, l);
            input.outputsToOrNode.put(id, l);
            return l;
        }
    }


    public static class Link {
        public OrEntry oe;

        public NodeActivation<?> input;
        public OrActivation output;

        public Link(OrEntry oe, NodeActivation<?> input, OrActivation output) {
            this.oe = oe;
            this.input = input;
            this.output = output;
        }
    }
}
