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
package org.aika.neuron;

import org.aika.corpus.Document;
import org.aika.corpus.InterpretationNode;
import org.aika.corpus.Range;
import org.aika.lattice.Node;
import org.aika.lattice.NodeActivation;

import java.util.Collection;
import java.util.Collections;
import java.util.stream.Stream;

import static org.aika.corpus.Range.Operator.EQUALS;
import static org.aika.lattice.Node.MAX_NODE;
import static org.aika.lattice.Node.MIN_NODE;


/**
 * The {@code Selector} class retrieves activations either from the document or the associated neuron.
 *
 * @author Lukas Molzberger
 */
public class Selector {


    public static Activation get(Document doc, INeuron n, Integer rid, Range r, Range.Relation rr, InterpretationNode o, InterpretationNode.Relation or) {
        Stream<Activation> s = select(doc, n, rid, r, rr, o, or);
        return s.findFirst()
                .orElse(null);
    }


    public static Activation get(Document doc, INeuron n, NodeActivation.Key ak) {
        return get(doc, n, ak.rid, ak.range, Range.Relation.EQUALS, ak.interpretation, InterpretationNode.Relation.EQUALS);
    }


    public static Stream<Activation> select(Document doc, INeuron n, Integer rid, Range r, Range.Relation rr, InterpretationNode o, InterpretationNode.Relation or) {
        INeuron.ThreadState th = n.getThreadState(doc.threadId, false);
        if (th == null) return Stream.empty();
        return select(th, n, rid, r, rr, o, or);
    }


    public static Stream<Activation> select(INeuron.ThreadState th, INeuron n, Integer rid, Range r, Range.Relation rr, InterpretationNode o, InterpretationNode.Relation or) {
        Stream<Activation> results;
        int s = th.activations.size();

        if (s == 0) return Stream.empty();
        else if (s == 1) {
            results = th.activations.values().stream();
        } else if (rid != null) {
            return getActivationsByRid(th, n, rid).stream();
        } else if(rr != null) {
            results = getActivationsByRange(th, n, r, rr).stream();
        } else {
            results = th.activations.values().stream();
        }

        return results.filter(act -> act.filter(n.node.get(), rid, r, rr, o, or));
    }


    private static Collection<Activation> getActivationsByRid(INeuron.ThreadState th, INeuron n, Integer rid) {
        Node node = n.node.get();
        NodeActivation.Key bk = new NodeActivation.Key(node, Range.MIN, rid, InterpretationNode.MIN);
        NodeActivation.Key ek = new NodeActivation.Key(node, Range.MAX, rid, InterpretationNode.MAX);

        if (th.activationsRid != null) {
            return th.activationsRid.subMap(bk, true, ek, true).values();
        } else {
            return Collections.EMPTY_LIST;
        }
    }


    public static Collection<Activation> getActivationsByRange(INeuron.ThreadState th, INeuron n, Range r, Range.Relation rr) {
        Node node = n.node.get();
        if(rr.beginToBegin == EQUALS || rr.beginToEnd == EQUALS || rr.endToBegin == EQUALS || rr.endToEnd == EQUALS) {
            return getActivationsByRangeEquals(th, n, r, rr);
        } else if (((rr.beginToBegin.isGreaterThanOrGreaterThanEqual() || rr.beginToEnd.isGreaterThanOrGreaterThanEqual())) && r.begin <= r.end) {
            return getActivationsByRangeBeginGreaterThan(th, r, rr, node);
        } else if (((rr.endToEnd.isGreaterThanOrGreaterThanEqual() || rr.endToBegin.isGreaterThanOrGreaterThanEqual())) && r.begin >= r.end) {
            return getActivationsByRangeEndGreaterThan(th, r, rr, node);
        } else if ((rr.beginToBegin.isLessThanOrLessThanEqual() || rr.beginToEnd.isLessThanOrLessThanEqual()) && r.begin <= r.end) {
            return getActivationsByRangeBeginLessThanEqual(th, r, rr, node);
        } else if ((rr.endToEnd.isLessThanOrLessThanEqual() || rr.endToBegin.isLessThanOrLessThanEqual()) && r.begin >= r.end) {
            return getActivationsByRangeEndLessThanEqual(th, r, rr, node);
        } else {
            return th.activations.values();
        }
    }


    private static Collection<Activation> getActivationsByRangeBeginGreaterThan(INeuron.ThreadState th, Range r, Range.Relation rr, Node node) {
        int fromKey;
        boolean fromInclusive;

        if(rr.beginToBegin.isGreaterThanOrGreaterThanEqual()) {
            fromKey = r.begin;
            fromInclusive = rr.beginToBegin.includesEqual();
        } else {
            fromKey = r.end;
            fromInclusive = rr.beginToEnd.includesEqual();
        }

        int toKey;
        boolean toInclusive;
        if(rr.endToEnd.isLessThanOrLessThanEqual()) {
            toKey = r.end;
            toInclusive = rr.endToEnd.includesEqual();
        } else if(rr.endToBegin.isLessThanOrLessThanEqual()) {
            toKey = r.begin;
            toInclusive = rr.endToBegin.includesEqual();
        } else {
            toKey = Integer.MAX_VALUE;
            toInclusive = true;
        }

        return th.activations.subMap(
                new NodeActivation.Key(node, new Range(fromKey, Integer.MIN_VALUE), null, InterpretationNode.MIN),
                fromInclusive,
                new NodeActivation.Key(node, new Range(toKey, Integer.MAX_VALUE), Integer.MAX_VALUE, InterpretationNode.MAX),
                toInclusive
        ).values();
    }


    private static Collection<Activation> getActivationsByRangeEndGreaterThan(INeuron.ThreadState th, Range r, Range.Relation rr, Node node) {
        int fromKey;
        boolean fromInclusive;

        if(rr.endToEnd.isGreaterThanOrGreaterThanEqual()) {
            fromKey = r.end;
            fromInclusive = rr.endToEnd.includesEqual();
        } else {
            fromKey = r.begin;
            fromInclusive = rr.endToBegin.includesEqual();
        }

        int toKey;
        boolean toInclusive;
        if(rr.beginToBegin.isLessThanOrLessThanEqual()) {
            toKey = r.begin;
            toInclusive = rr.beginToBegin.includesEqual();
        } else if(rr.beginToEnd.isLessThanOrLessThanEqual()) {
            toKey = r.end;
            toInclusive = rr.beginToEnd.includesEqual();
        } else {
            toKey = Integer.MAX_VALUE;
            toInclusive = true;
        }

        return th.activationsEnd.subMap(
                new NodeActivation.Key(node, new Range(Integer.MIN_VALUE, fromKey), null, InterpretationNode.MIN),
                fromInclusive,
                new NodeActivation.Key(node, new Range(Integer.MAX_VALUE, toKey), Integer.MAX_VALUE, InterpretationNode.MAX),
                toInclusive
        ).values();
    }


    private static Collection<Activation> getActivationsByRangeBeginLessThanEqual(INeuron.ThreadState th, Range r, Range.Relation rr, Node node) {
        int fromKey;
        boolean fromInclusive;
        if(rr.endToEnd.isGreaterThanOrGreaterThanEqual()) {
            fromKey = r.end - th.maxLength;
            fromInclusive = rr.endToEnd.includesEqual();
        } else if(rr.endToBegin.isGreaterThanOrGreaterThanEqual()) {
            fromKey = r.begin - th.maxLength;
            fromInclusive = rr.endToBegin.includesEqual();
        } else {
            fromKey = Integer.MIN_VALUE;
            fromInclusive = true;
        }

        int toKey;
        boolean toInclusive;

        if(rr.beginToBegin.isLessThanOrLessThanEqual()) {
            toKey = r.begin;
            toInclusive = rr.beginToBegin.includesEqual();
        } else {
            toKey = r.end;
            toInclusive = rr.beginToEnd.includesEqual();
        }

        if(fromKey > toKey) return Collections.EMPTY_LIST;

        return th.activations.subMap(
                new NodeActivation.Key(node, new Range(fromKey, Integer.MIN_VALUE), null, InterpretationNode.MIN),
                fromInclusive,
                new NodeActivation.Key(node, new Range(toKey, Integer.MAX_VALUE), null, InterpretationNode.MAX),
                toInclusive
        ).values();
    }


    private static Collection<Activation> getActivationsByRangeEndLessThanEqual(INeuron.ThreadState th, Range r, Range.Relation rr, Node node) {
        int fromKey;
        boolean fromInclusive;
        if(rr.beginToEnd.isGreaterThanOrGreaterThanEqual()) {
            fromKey = r.end - th.maxLength;
            fromInclusive = rr.beginToEnd.includesEqual();
        } else if(rr.beginToBegin.isGreaterThanOrGreaterThanEqual()) {
            fromKey = r.begin - th.maxLength;
            fromInclusive = rr.beginToBegin.includesEqual();
        } else {
            fromKey = Integer.MIN_VALUE;
            fromInclusive = true;
        }

        int toKey;
        boolean toInclusive;

        if(rr.endToBegin.isLessThanOrLessThanEqual()) {
            toKey = r.begin;
            toInclusive = rr.endToBegin.includesEqual();
        } else {
            toKey = r.end;
            toInclusive = rr.endToEnd.includesEqual();
        }

        if(fromKey > toKey) return Collections.EMPTY_LIST;

        return th.activationsEnd.subMap(
                new NodeActivation.Key(node, new Range(Integer.MIN_VALUE, fromKey), null, InterpretationNode.MIN),
                fromInclusive,
                new NodeActivation.Key(node, new Range(Integer.MAX_VALUE, toKey), null, InterpretationNode.MAX),
                toInclusive
        ).values();
    }



    public static Collection<Activation> getActivationsByRangeEquals(INeuron.ThreadState th, INeuron n, Range r, Range.Relation rr) {
        Node node = n.node.get();
        if(rr.beginToBegin == EQUALS || rr.beginToEnd == EQUALS) {
            int key = rr.beginToBegin == EQUALS ? r.begin : r.end;
            return th.activations.subMap(
                    new NodeActivation.Key(node, new Range(key, Integer.MIN_VALUE), null, InterpretationNode.MIN),
                    true,
                    new NodeActivation.Key(node, new Range(key, Integer.MAX_VALUE), Integer.MAX_VALUE, InterpretationNode.MAX),
                    true
            ).values();
        } else if(rr.endToEnd == EQUALS || rr.endToBegin == EQUALS) {
            int key = rr.endToEnd == EQUALS ? r.end : r.begin;
            return th.activationsEnd.subMap(
                    new NodeActivation.Key(node, new Range(Integer.MIN_VALUE, key), null, InterpretationNode.MIN),
                    true,
                    new NodeActivation.Key(node, new Range(Integer.MAX_VALUE, key), Integer.MAX_VALUE, InterpretationNode.MAX),
                    true
            ).values();
        }
        throw new RuntimeException("Invalid Range Relation");
    }


    public static Collection<Activation> select(Linker.SortGroup sortGroup, Activation act) {
        switch(sortGroup) {
            case RANGE_BEGIN: {
                NodeActivation.Key bk = new NodeActivation.Key(MIN_NODE, new Range(act.key.range.begin, Integer.MIN_VALUE), Integer.MIN_VALUE, InterpretationNode.MIN);
                NodeActivation.Key ek = new NodeActivation.Key(MAX_NODE, new Range(act.key.range.begin, Integer.MAX_VALUE), Integer.MAX_VALUE, InterpretationNode.MAX);

                return act.doc.activationsByRangeBegin.subMap(bk, true, ek, true).values();
            }
            case RANGE_END: {
                NodeActivation.Key bk = new NodeActivation.Key(MIN_NODE, new Range(Integer.MIN_VALUE, act.key.range.end), Integer.MIN_VALUE, InterpretationNode.MIN);
                NodeActivation.Key ek = new NodeActivation.Key(MAX_NODE, new Range(Integer.MAX_VALUE, act.key.range.end), Integer.MAX_VALUE, InterpretationNode.MAX);

                return act.doc.activationsByRangeEnd.subMap(bk, true, ek, true).values();
            }
            case RID_ZERO: {
                NodeActivation.Key bk = new NodeActivation.Key(MIN_NODE, Range.MIN, act.key.rid, InterpretationNode.MIN);
                NodeActivation.Key ek = new NodeActivation.Key(MAX_NODE, Range.MAX, act.key.rid, InterpretationNode.MAX);

                return act.doc.activationsByRid.subMap(bk, true, ek, true).values();
            }
            default:
                throw new RuntimeException("Unsupported Sort Group.");
        }
    }
}
