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
package network.aika.network;


import network.aika.ActivationFunction;
import network.aika.Document;
import network.aika.Model;
import network.aika.neuron.Neuron;
import network.aika.neuron.Synapse;
import network.aika.neuron.activation.Activation;
import network.aika.neuron.activation.Range;
import network.aika.lattice.NodeActivation;
import network.aika.neuron.activation.Range.Operator;
import network.aika.neuron.INeuron;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Lukas Molzberger
 */
public class SimplePatternMatchingTest {

    @Test
    public void testPatternMatching1() {
        Model m = new Model();

        Map<Character, Neuron> inputNeurons = new HashMap<>();

        // Create an input neuron and a recurrent neuron for every letter in this example.
        for(char c: new char[] {'a', 'b', 'c', 'd', 'e'}) {
            Neuron in = m.createNeuron(c + "");

            inputNeurons.put(c, in);
        }

        // Create a pattern neuron with the relational neurons as input. The numbers that are
        // given in the inputs are the recurrent ids (relativeRid) which specify the relative position
        // of the inputs relative to each other. The following flag specifies whether this relativeRid
        // is relative or absolute.
        Neuron pattern = Neuron.init(
                m.createNeuron("BCD"),
                0.4,
                INeuron.Type.EXCITATORY,
                new Synapse.Builder()
                        .setSynapseId(0)
                        .setNeuron(inputNeurons.get('b'))
                        .setWeight(1.0)
                        .setBias(-0.9)
                        .setRecurrent(false)
                        .addRangeRelation(Range.Relation.END_TO_BEGIN_EQUALS, 1)
                        .setRangeOutput(true, false),
                new Synapse.Builder()
                        .setSynapseId(1)
                        .setNeuron(inputNeurons.get('c'))
                        .setWeight(1.0)
                        .setBias(-0.9)
                        .setRecurrent(false)
                        .addRangeRelation(Range.Relation.END_TO_BEGIN_EQUALS, 2),
                new Synapse.Builder()
                        .setSynapseId(2)
                        .setNeuron(inputNeurons.get('d'))
                        .setWeight(1.0)
                        .setBias(-0.9)
                        .setRecurrent(false)
                        .setRangeOutput(false, true)
        );


        // Create a simple text document.
        Document doc = m.createDocument("a b c d e ", 0);

        // Then add the characters
        int wordPos = 0;
        for(int i = 0; i < doc.length(); i++) {
            char c = doc.getContent().charAt(i);
            if(c != ' ') {
                inputNeurons.get(c).addInput(doc, i, i + 2);
            } else {
                wordPos++;
            }
        }

        // Computes the selected option
        doc.process();

        Assert.assertEquals(1, pattern.get().getThreadState(doc.threadId, true).activations.size());


        System.out.println("Output activation:");
        INeuron n = pattern.get();
        for(Activation act: n.getActivations(doc, false)) {
            System.out.println("Text Range: " + act.range);
            System.out.println("Node: " + act.node);
            System.out.println();
        }

        System.out.println("All activations:");
        System.out.println(doc.activationsToString(true, false, true));
        System.out.println();

        doc.clearActivations();
    }


    @Test
    public void testPatternMatching2() {
        Model m = new Model();

        Map<Character, Neuron> inputNeurons = new HashMap<>();

        // Create an input neuron and a recurrent neuron for every letter in this example.
        for(char c: new char[] {'a', 'b', 'c', 'd', 'e', 'f'}) {
            Neuron in = m.createNeuron(c + "");

            inputNeurons.put(c, in);
        }

        // Create a pattern neuron with the relational neurons as input. The numbers that are
        // given in the inputs are the recurrent ids (relativeRid) which specify the relative position
        // of the inputs relative to each other. The following flag specifies whether this relativeRid
        // is relative or absolute.
        Neuron pattern = Neuron.init(
                m.createNeuron("BCDE"),
                5.0,
                ActivationFunction.RECTIFIED_HYPERBOLIC_TANGENT,
                INeuron.Type.EXCITATORY,
                new Synapse.Builder()
                        .setSynapseId(0)
                        .setNeuron(inputNeurons.get('b'))
                        .setWeight(10.0)
                        .setBias(-10.0)
                        .setRecurrent(false)
                        .addRangeRelation(Range.Relation.END_TO_BEGIN_EQUALS, 1)
                        .setRangeOutput(true, false),
                new Synapse.Builder()
                        .setSynapseId(1)
                        .setNeuron(inputNeurons.get('c'))
                        .setWeight(10.0)
                        .setBias(-10.0)
                        .setRecurrent(false)
                        .addRangeRelation(Range.Relation.END_TO_BEGIN_EQUALS, 2),
                new Synapse.Builder()
                        .setSynapseId(2)
                        .setNeuron(inputNeurons.get('d'))
                        .setWeight(10.0)
                        .setBias(-10.0)
                        .setRecurrent(false)
                        .addRangeRelation(Range.Relation.END_TO_BEGIN_EQUALS, 3),
                new Synapse.Builder()
                        .setSynapseId(3)
                        .setNeuron(inputNeurons.get('e'))
                        .setWeight(10.0)
                        .setBias(-10.0)
                        .setRecurrent(false)
                        .setRangeOutput(false, true)
        );


        // Create a simple text document.
        Document doc = m.createDocument("a b c d e ", 0);

        // Then add the characters
        int wordPos = 0;
        for(int i = 0; i < doc.length(); i++) {
            char c = doc.getContent().charAt(i);
            if(c != ' ') {
                inputNeurons.get(c).addInput(doc, i, i + 2);
            } else {
                wordPos++;
            }
        }

        // Computes the best interpretation
        doc.process();

        Assert.assertEquals(1, pattern.getActivations(doc, false).size());


        System.out.println("Output activation:");
        INeuron n = pattern.get();
        for(Activation act: n.getActivations(doc, false)) {
            System.out.println("Text Range: " + act.range);
            System.out.println("Node: " + act.node);
            System.out.println();
        }

        System.out.println("All activations:");
        System.out.println(doc.activationsToString(true, false, true));
        System.out.println();

        doc.clearActivations();
    }
    
}
