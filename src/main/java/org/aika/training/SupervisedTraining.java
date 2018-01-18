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
package org.aika.training;


import org.aika.Provider;
import org.aika.corpus.Document;
import org.aika.lattice.InputNode;
import org.aika.neuron.Activation;
import org.aika.neuron.INeuron;
import org.aika.neuron.Synapse;
import org.aika.training.SynapseEvaluation.Result;

import java.util.TreeSet;

/**
 *
 * @author Lukas Molzberger
 */
public class SupervisedTraining {

    public Document doc;

    public TreeSet<Activation> targetActivations = new TreeSet<>();
    public TreeSet<Activation> errorSignalActivations = new TreeSet<>();

    public BackPropagationQueue queue = new BackPropagationQueue();


    public SupervisedTraining(Document doc) {
        this.doc = doc;
    }


    public static class TrainConfig {
        public SynapseEvaluation synapseEvaluation;
        public double learnRate;
        public boolean performBackpropagation;


        /**
         * Determines whether a synapse should be created between two neurons during training.
         *
         * @param synapseEvaluation
         * @return
         */
        public TrainConfig setSynapseEvaluation(SynapseEvaluation synapseEvaluation) {
            this.synapseEvaluation = synapseEvaluation;
            return this;
        }


        public TrainConfig setLearnRate(double learnRate) {
            this.learnRate = learnRate;
            return this;
        }


        public TrainConfig setPerformBackpropagation(boolean performBackpropagation) {
            this.performBackpropagation = performBackpropagation;
            return this;
        }
    }


    public void train(TrainConfig trainConfig) {
        targetActivations.forEach(tAct -> computeOutputErrorSignal(tAct));

        if(trainConfig.performBackpropagation) {
            queue.backpropagtion();
        }

        for (Activation act : errorSignalActivations) {
            train(act.key.node.neuron.get(doc), act, trainConfig.learnRate, trainConfig.synapseEvaluation);
        }
        errorSignalActivations.clear();
    }



    public void train(INeuron n, Activation targetAct, double learnRate, SynapseEvaluation se) {
        if (Math.abs(targetAct.errorSignal) < INeuron.TOLERANCE) return;

        long v = doc.visitedCounter++;

        double x = learnRate * targetAct.errorSignal;
        n.biasDelta += x;
        doc.getFinalActivations().forEach(iAct -> {
            Result ser = se.evaluate(null, iAct, targetAct);
            if (ser != null) {
                trainSynapse(n, iAct, ser, x, v);
            }
        });

        doc.notifyWeightsModified(n, n.provider.inMemoryInputSynapses.values());
    }



    public void computeOutputErrorSignal(Activation act) {
        if(act.targetValue != null) {
            act.errorSignal += act.targetValue - act.getFinalState().value;
        }

        updateErrorSignal(act);
    }


    public void computeBackpropagationErrorSignal(Activation act) {
        for (Activation.SynapseActivation sa : act.neuronOutputs) {
            Synapse s = sa.synapse;
            Activation oAct = sa.output;

            act.errorSignal += s.weight * oAct.errorSignal * (1.0 - act.getFinalState().value);
        }

        updateErrorSignal(act);
    }


    public void updateErrorSignal(Activation act) {
        if(act.errorSignal != 0.0) {
            errorSignalActivations.add(act);
            for (Activation.SynapseActivation sa : act.neuronInputs) {
                queue.add(sa.input);
            }
        }
    }


    private void trainSynapse(INeuron n, Activation iAct, Result ser, double x, long v) {
        if (iAct.visited == v) return;
        iAct.visited = v;

        INeuron inputNeuron = iAct.key.node.neuron.get(doc);
        if(inputNeuron == n) {
            return;
        }
        double deltaW = x * ser.significance * iAct.getFinalState().value;


        Synapse synapse = Synapse.createOrLookup(ser.synapseKey, inputNeuron.provider, n.provider);

        synapse.weightDelta = (float) deltaW;
    }




    public class BackPropagationQueue {

        public final TreeSet<Activation> queue = new TreeSet<>((act1, act2) -> {
            Activation.State fs1 = act1.getFinalState();
            Activation.State fs2 = act2.getFinalState();

            int r = Integer.compare(fs2.fired, fs1.fired);
            if (r != 0) return r;
            return act1.key.compareTo(act2.key);
        });

        private long queueIdCounter = 0;


        public void add(Activation act) {
            if(!act.isQueued) {
                act.isQueued = true;
                act.queueId = queueIdCounter++;
                queue.add(act);
            }
        }


        public void backpropagtion() {
            while(!queue.isEmpty()) {
                Activation act = queue.pollFirst();

                act.isQueued = false;
                computeBackpropagationErrorSignal(act);
            }
        }
    }

}
