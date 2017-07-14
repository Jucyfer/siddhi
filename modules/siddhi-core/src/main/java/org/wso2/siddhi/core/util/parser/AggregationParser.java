/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.util.parser;

import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.event.stream.StreamEventCloner;
import org.wso2.siddhi.core.event.stream.StreamEventPool;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.executor.ExpressionExecutor;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.input.stream.StreamRuntime;
import org.wso2.siddhi.core.query.input.stream.single.EntryValveExecutor;
import org.wso2.siddhi.core.query.input.stream.single.SingleStreamRuntime;
import org.wso2.siddhi.core.query.selector.GroupByKeyGenerator;
import org.wso2.siddhi.core.query.selector.attribute.aggregator.incremental.IncrementalAggregationProcessor;
import org.wso2.siddhi.core.query.selector.attribute.aggregator.incremental.IncrementalAttributeAggregator;
import org.wso2.siddhi.core.query.selector.attribute.aggregator.incremental.IncrementalExecutor;
import org.wso2.siddhi.core.table.InMemoryTable;
import org.wso2.siddhi.core.table.Table;
import org.wso2.siddhi.core.util.Scheduler;
import org.wso2.siddhi.core.util.SiddhiClassLoader;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.extension.holder.IncrementalAttributeAggregatorExtensionHolder;
import org.wso2.siddhi.core.util.lock.LockWrapper;
import org.wso2.siddhi.core.util.parser.helper.QueryParserHelper;
import org.wso2.siddhi.core.util.statistics.LatencyTracker;
import org.wso2.siddhi.core.window.Window;
import org.wso2.siddhi.query.api.aggregation.TimePeriod;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.definition.AggregationDefinition;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.siddhi.query.api.definition.TableDefinition;
import org.wso2.siddhi.query.api.execution.query.selection.OutputAttribute;
import org.wso2.siddhi.query.api.expression.AttributeFunction;
import org.wso2.siddhi.query.api.expression.Expression;
import org.wso2.siddhi.query.api.expression.Variable;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/*
 * This is the parer class of incremental aggregation definition.
 */
public class AggregationParser {

    public static AggregationRuntime parse(AggregationDefinition aggregationDefinition,
                                           SiddhiAppContext siddhiAppContext,
                                           Map<String, AbstractDefinition> streamDefinitionMap,
                                           Map<String, AbstractDefinition> tableDefinitionMap,
                                           Map<String, AbstractDefinition> windowDefinitionMap,
                                           Map<String, Table> tableMap,
                                           Map<String, Window> windowMap) {

        if (aggregationDefinition == null) {
            throw new SiddhiAppCreationException(
                    "AggregationDefinition instance is null. " +
                            "Hence, can't create the siddhi app '" + siddhiAppContext.getName() + "'");
        }
        if (aggregationDefinition.getTimePeriod() == null) {
            throw new SiddhiAppCreationException(
                    "AggregationDefinition '" + aggregationDefinition.getId() + "'s timePeriod is null. " +
                            "Hence, can't create the siddhi app '" + siddhiAppContext.getName() + "'");
        }
        if (aggregationDefinition.getSelector() == null) {
            throw new SiddhiAppCreationException(
                    "AggregationDefinition '" + aggregationDefinition.getId() + "'s selection is not defined. " +
                            "Hence, can't create the siddhi app '" + siddhiAppContext.getName() + "'");
        }

        List<VariableExpressionExecutor> incomingVariableExpressionExecutors = new ArrayList<>();

        String aggregatorName = aggregationDefinition.getId();
        LatencyTracker latencyTracker = QueryParserHelper.getLatencyTracker(siddhiAppContext, aggregatorName,
                SiddhiConstants.METRIC_INFIX_AGGRIGATIONS);

        StreamRuntime streamRuntime = InputStreamParser.parse(aggregationDefinition.getBasicSingleInputStream(),
                siddhiAppContext, streamDefinitionMap, tableDefinitionMap, windowDefinitionMap, tableMap, windowMap,
                incomingVariableExpressionExecutors, latencyTracker, false, aggregatorName);

        // Get original meta for later use.
        MetaStreamEvent incomingMetaStreamEvent = (MetaStreamEvent) streamRuntime.getMetaComplexEvent();
        // Create new meta stream event.
        // This must hold the timestamp, group by attributes (if given) and the incremental attributes, in
        // onAfterWindowData array
        // Example format: _TIMESTAMP, groupByAttribute1, groupByAttribute2, _incAttribute1, _incAttribute2
        // _incAttribute1, _incAttribute2 would have the same attribute names as in finalListOfIncrementalAttributes
        incomingMetaStreamEvent.initializeAfterWindowData(); // To enter data as onAfterWindowData

        List<ExpressionExecutor> incomingExpressionExecutors = new ArrayList<>();
        List<IncrementalAttributeAggregator> incrementalAttributeAggregators = new ArrayList<>();
        List<Variable> groupByVariableList = aggregationDefinition.getSelector().getGroupByList();
        boolean isProcessingOnExternalTime = aggregationDefinition.getAggregateAttribute() != null;

        populateIncomingAggregatorsAndExecutors(aggregationDefinition, siddhiAppContext, tableMap,
                incomingVariableExpressionExecutors, aggregatorName, incomingMetaStreamEvent,
                incomingExpressionExecutors, incrementalAttributeAggregators, groupByVariableList);

        int baseAggregatorBeginIndex = incomingMetaStreamEvent.getOutputData().size();

        List<Expression> finalBaseAggregators = getFinalBaseAggregators(siddhiAppContext, tableMap,
                incomingVariableExpressionExecutors, aggregatorName, incomingMetaStreamEvent,
                incomingExpressionExecutors, incrementalAttributeAggregators);

        StreamDefinition incomingOutputStreamDefinition = StreamDefinition.id("");
        MetaStreamEvent processedMetaStreamEvent = new MetaStreamEvent();
        for (Attribute attribute : incomingMetaStreamEvent.getOutputData()) {
            incomingOutputStreamDefinition.attribute(attribute.getName(), attribute.getType());
            processedMetaStreamEvent.addOutputData(attribute);
        }
        incomingMetaStreamEvent.setOutputDefinition(incomingOutputStreamDefinition);
        processedMetaStreamEvent.addInputDefinition(incomingOutputStreamDefinition);
        processedMetaStreamEvent.setOutputDefinition(incomingOutputStreamDefinition);

        // Executors of processing meta
        List<VariableExpressionExecutor> processVariableExpressionExecutors = new ArrayList<>();
        boolean groupBy = aggregationDefinition.getSelector().getGroupByList().size() != 0;

        List<ExpressionExecutor> processExpressionExecutors = constructProcessExpressionExecutors(
                siddhiAppContext, tableMap, aggregatorName, baseAggregatorBeginIndex,
                finalBaseAggregators, incomingOutputStreamDefinition, processedMetaStreamEvent,
                processVariableExpressionExecutors, groupBy);

        // Create group by key generator
        GroupByKeyGenerator groupByKeyGenerator = null;
        if (groupBy) {
            groupByKeyGenerator = new GroupByKeyGenerator(groupByVariableList, processedMetaStreamEvent, tableMap,
                    processVariableExpressionExecutors, siddhiAppContext, aggregatorName);
        }

        // Create stream event pool
        //  StreamEventPool processStreamEventPool = new StreamEventPool(processedMetaStreamEvent, 10);

        int bufferSize = 0;
        Element element = AnnotationHelper.getAnnotationElement(SiddhiConstants.ANNOTATION_BUFFER_SIZE, null,
                aggregationDefinition.getAnnotations());
        if (element != null) {
            bufferSize = Integer.parseInt(element.getValue());
        }

        // Create in-memory default table definitions and add to tableMap // TODO: 6/11/17 must later be taken from
        // @store. Optional?
        List<TimePeriod.Duration> incrementalDurations = getSortedPeriods(aggregationDefinition.getTimePeriod());
        initDefaultTables(tableMap, aggregatorName, incrementalDurations,
                processedMetaStreamEvent, siddhiAppContext);

        IncrementalExecutor rootIncrementalExecutor = buildIncrementalExecutorChain(
                siddhiAppContext, aggregatorName, isProcessingOnExternalTime,
                processedMetaStreamEvent, processExpressionExecutors, groupByKeyGenerator,
                bufferSize, incrementalDurations);


        // Create new scheduler
        EntryValveExecutor entryValveExecutor = new EntryValveExecutor(siddhiAppContext);
        LockWrapper lockWrapper = new LockWrapper(aggregatorName);
        lockWrapper.setLock(new ReentrantLock());

        Scheduler scheduler = SchedulerParser.parse(siddhiAppContext.getScheduledExecutorService(),
                entryValveExecutor, siddhiAppContext);
        scheduler.init(lockWrapper, aggregatorName);
        scheduler.setStreamEventPool(new StreamEventPool(processedMetaStreamEvent, 10));
        rootIncrementalExecutor.setScheduler(scheduler);

        // Connect entry valve to root incremental executor
        entryValveExecutor.setNextExecutor(rootIncrementalExecutor);

        QueryParserHelper.reduceMetaComplexEvent(incomingMetaStreamEvent);
        QueryParserHelper.reduceMetaComplexEvent(processedMetaStreamEvent);
        QueryParserHelper.updateVariablePosition(incomingMetaStreamEvent, incomingVariableExpressionExecutors);
        QueryParserHelper.updateVariablePosition(processedMetaStreamEvent, processVariableExpressionExecutors);
        QueryParserHelper.initStreamRuntime(streamRuntime, incomingMetaStreamEvent, lockWrapper, aggregatorName);

        streamRuntime.setCommonProcessor(new IncrementalAggregationProcessor(rootIncrementalExecutor,
                incomingExpressionExecutors, processedMetaStreamEvent));

        AggregationRuntime aggregationRuntime = new AggregationRuntime(aggregationDefinition, siddhiAppContext,
                ((SingleStreamRuntime) streamRuntime), entryValveExecutor);

        return aggregationRuntime;
    }

    private static IncrementalExecutor buildIncrementalExecutorChain(
            SiddhiAppContext siddhiAppContext, String aggregatorName, boolean isProcessingOnExternalTime,
            MetaStreamEvent processedMetaStreamEvent, List<ExpressionExecutor> processExpressionExecutors,
            GroupByKeyGenerator groupByKeyGenerator, int bufferSize, List<TimePeriod.Duration> incrementalDurations) {
        // Create incremental executors
        IncrementalExecutor child;
        IncrementalExecutor root = null;
        for (int i = incrementalDurations.size() - 1; i >= 0; i--) {
            // Base incremental expression executors created using new meta
            boolean isRoot = false;
            if (i == 0) {
                isRoot = true;
            }
            child = root;
            root = new IncrementalExecutor(incrementalDurations.get(i),
                    cloneExpressionExecutors(processExpressionExecutors),
                    groupByKeyGenerator, processedMetaStreamEvent, bufferSize, aggregatorName, child, isRoot,
                    siddhiAppContext, isProcessingOnExternalTime);
        }
        return root;
    }

    private static List<ExpressionExecutor> constructProcessExpressionExecutors(
            SiddhiAppContext siddhiAppContext, Map<String, Table> tableMap,
            String aggregatorName, int baseAggregatorBeginIndex,
            List<Expression> finalBaseAggregators,
            StreamDefinition incomingOutputStreamDefinition,
            MetaStreamEvent processedMetaStreamEvent,
            List<VariableExpressionExecutor> processVariableExpressionExecutors, boolean groupBy) {
        List<ExpressionExecutor> processExpressionExecutors = new ArrayList<>();
        List<Attribute> attributeList = incomingOutputStreamDefinition.getAttributeList();
        for (int i = 0; i < baseAggregatorBeginIndex; i++) {
            Attribute attribute = attributeList.get(i);
            VariableExpressionExecutor variableExpressionExecutor = (VariableExpressionExecutor) ExpressionParser
                    .parseExpression(new Variable(attribute.getName()), processedMetaStreamEvent, 0,
                            tableMap, processVariableExpressionExecutors, siddhiAppContext, groupBy,
                            0, aggregatorName);
            processExpressionExecutors.add(variableExpressionExecutor);
        }

        for (Expression expression : finalBaseAggregators) {
            ExpressionExecutor expressionExecutor = ExpressionParser.parseExpression(expression,
                    processedMetaStreamEvent, 0, tableMap, processVariableExpressionExecutors,
                    siddhiAppContext, groupBy, 0, aggregatorName);
            processExpressionExecutors.add(expressionExecutor);
        }
        return processExpressionExecutors;
    }

    private static List<Expression> getFinalBaseAggregators(
            SiddhiAppContext siddhiAppContext, Map<String, Table> tableMap,
            List<VariableExpressionExecutor> incomingVariableExpressionExecutors, String aggregatorName,
            MetaStreamEvent incomingMetaStreamEvent, List<ExpressionExecutor> incomingExpressionExecutors,
            List<IncrementalAttributeAggregator> incrementalAttributeAggregators) {
        List<Attribute> finalBaseAttributes = new ArrayList<>();
        List<Expression> finalBaseAggregators = new ArrayList<>();

        for (IncrementalAttributeAggregator incrementalAttributeAggregator : incrementalAttributeAggregators) {
            Attribute[] baseAttributes = incrementalAttributeAggregator.getBaseAttributes();
            Expression[] baseAttributeInitialValues = incrementalAttributeAggregator.getBaseAttributeInitialValues();
            Expression[] baseAggregators = incrementalAttributeAggregator.getBaseAggregators();
            for (int i = 0; i < baseAttributes.length; i++) {
                validateBaseAggregators(incrementalAttributeAggregators,
                        incrementalAttributeAggregator, baseAttributes,
                        baseAttributeInitialValues, baseAggregators, i);
                if (!finalBaseAttributes.contains(baseAttributes[i])) {
                    finalBaseAttributes.add(baseAttributes[i]);
                    finalBaseAggregators.add(baseAggregators[i]);
                    incomingMetaStreamEvent.addOutputData(baseAttributes[i]);
                    incomingExpressionExecutors.add(ExpressionParser.parseExpression(baseAttributeInitialValues[i],
                            incomingMetaStreamEvent, 0, tableMap, incomingVariableExpressionExecutors,
                            siddhiAppContext, false, 0, aggregatorName));
                }
            }
        }
        return finalBaseAggregators;
    }

    private static void populateIncomingAggregatorsAndExecutors(
            AggregationDefinition aggregationDefinition, SiddhiAppContext siddhiAppContext,
            Map<String, Table> tableMap, List<VariableExpressionExecutor> incomingVariableExpressionExecutors,
            String aggregatorName, MetaStreamEvent incomingMetaStreamEvent,
            List<ExpressionExecutor> incomingExpressionExecutors,
            List<IncrementalAttributeAggregator> incrementalAttributeAggregators, List<Variable> groupByVariableList) {
        ExpressionExecutor timestampExecutor = getTimestampExecutor(aggregationDefinition, siddhiAppContext,
                tableMap, incomingVariableExpressionExecutors, aggregatorName, incomingMetaStreamEvent);
        incomingMetaStreamEvent.addOutputData(new Attribute("_TIMESTAMP", Attribute.Type.LONG));
        incomingExpressionExecutors.add(timestampExecutor);

        AbstractDefinition incomingLastInputStreamDefinition = incomingMetaStreamEvent.getLastInputDefinition();
        for (Variable groupByVariable : groupByVariableList) {
            incomingMetaStreamEvent.addOutputData(incomingLastInputStreamDefinition.getAttributeList()
                    .get(incomingLastInputStreamDefinition.getAttributePosition(
                            groupByVariable.getAttributeName())));
            incomingExpressionExecutors.add(ExpressionParser.parseExpression(groupByVariable,
                    incomingMetaStreamEvent, 0, tableMap, incomingVariableExpressionExecutors,
                    siddhiAppContext, false, 0, aggregatorName));
        }

        for (OutputAttribute outputAttribute : aggregationDefinition.getSelector().getSelectionList()) {
            Expression expression = outputAttribute.getExpression();
            if (expression instanceof AttributeFunction) {
                IncrementalAttributeAggregator incrementalAttributeAggregator =
                        getIncrementalAttributeAggregator(siddhiAppContext, incomingLastInputStreamDefinition,
                                (AttributeFunction) expression);
                incrementalAttributeAggregators.add(incrementalAttributeAggregator);
            } else {
                if (!(expression instanceof Variable) && !groupByVariableList.contains(expression)) {
                    ExpressionExecutor expressionExecutor = ExpressionParser.parseExpression(expression,
                            incomingMetaStreamEvent, 0, tableMap, incomingVariableExpressionExecutors,
                            siddhiAppContext, false, 0, aggregatorName);
                    incomingExpressionExecutors.add(expressionExecutor);
                    incomingMetaStreamEvent.addOutputData(
                            new Attribute(outputAttribute.getRename(), expressionExecutor.getReturnType()));
                }
            }
        }
    }

    private static List<ExpressionExecutor> cloneExpressionExecutors(List<ExpressionExecutor> expressionExecutors) {
        List<ExpressionExecutor> arrayList = new ArrayList<>();
        for (ExpressionExecutor expressionExecutor : expressionExecutors) {
            arrayList.add(expressionExecutor.cloneExecutor(null));
        }
        return arrayList;
    }

    private static void validateBaseAggregators(List<IncrementalAttributeAggregator> incrementalAttributeAggregators,
                                                IncrementalAttributeAggregator incrementalAttributeAggregator,
                                                Attribute[] baseAttributes, Expression[] baseAttributeInitialValues,
                                                Expression[] baseAggregators, int i) {
        for (int i1 = i; i1 < incrementalAttributeAggregators.size(); i1++) {
            IncrementalAttributeAggregator otherAttributeAggregator = incrementalAttributeAggregators.get(i1);
            if (otherAttributeAggregator != incrementalAttributeAggregator) {
                Attribute[] otherBaseAttributes = otherAttributeAggregator.getBaseAttributes();
                Expression[] otherBaseAttributeInitialValues = otherAttributeAggregator
                        .getBaseAttributeInitialValues();
                Expression[] otherBaseAggregators = otherAttributeAggregator.getBaseAggregators();
                for (int j = 0; j < otherBaseAttributes.length; j++) {
                    if (baseAttributes[i].equals(otherBaseAttributes[j])) {
                        if (!baseAttributeInitialValues[i].equals(otherBaseAttributeInitialValues[j])) {
                            throw new SiddhiAppCreationException("BaseAttributes having same name should " +
                                    "be defined with same initial values, but baseAttribute '" +
                                    baseAttributes[i] + "' is defined in '" +
                                    incrementalAttributeAggregator.getClass().getName() + "' and '" +
                                    otherAttributeAggregator.getClass().getName() +
                                    "' with different initial values.");
                        }
                        if (!baseAggregators[i].equals(otherBaseAggregators[j])) {
                            throw new SiddhiAppCreationException("BaseAttributes having same name should " +
                                    "be defined with same baseAggregators, but baseAttribute '" +
                                    baseAttributes[i] + "' is defined in '" +
                                    incrementalAttributeAggregator.getClass().getName() + "' and '" +
                                    otherAttributeAggregator.getClass().getName() +
                                    "' with different baseAggregators.");
                        }
                    }
                }
            }
        }
    }

    private static IncrementalAttributeAggregator getIncrementalAttributeAggregator(
            SiddhiAppContext siddhiAppContext, AbstractDefinition lastInputStreamDefinition,
            AttributeFunction attributeFunction) {
        if (attributeFunction.getParameters() == null || attributeFunction.getParameters()[0] == null) {
            throw new SiddhiAppCreationException("Attribute function " + attributeFunction.getName()
                    + " cannot be executed when no parameters are given");
        }
        if (attributeFunction.getParameters().length != 1) {
            throw new SiddhiAppCreationException("Aggregation requires only on one parameter. "
                    + "Found " + attributeFunction.getParameters().length);
        }
        if (!(attributeFunction.getParameters()[0] instanceof Variable)) {
            throw new SiddhiAppCreationException("Expected a variable. However a parameter of type "
                    + attributeFunction.getParameters()[0].getClass().getTypeName() + " was found");
        }
        String attributeName = ((Variable) attributeFunction.getParameters()[0]).getAttributeName();

        IncrementalAttributeAggregator incrementalAttributeAggregator = (IncrementalAttributeAggregator)
                SiddhiClassLoader.loadExtensionImplementation(
                        new AttributeFunction("incrementalAggregator",
                                attributeFunction.getName(),
                                attributeFunction.getParameters()),
                        IncrementalAttributeAggregatorExtensionHolder.getInstance(siddhiAppContext));
        incrementalAttributeAggregator.init(attributeName,
                lastInputStreamDefinition.getAttributeType(attributeName));

        Attribute[] baseAttributes = incrementalAttributeAggregator.getBaseAttributes();
        Expression[] baseAttributeInitialValues = incrementalAttributeAggregator
                .getBaseAttributeInitialValues();
        Expression[] baseAggregators = incrementalAttributeAggregator.getBaseAggregators();

        if (baseAttributes.length != baseAggregators.length) {
            throw new SiddhiAppCreationException("Number of baseAggregators '" +
                    baseAggregators.length + "' and baseAttributes '" +
                    baseAttributes.length + "' is not equal for '" + attributeFunction + "'");
        }
        if (baseAttributeInitialValues.length != baseAggregators.length) {
            throw new SiddhiAppCreationException("Number of baseAggregators '" +
                    baseAggregators.length + "' and baseAttributeInitialValues '" +
                    baseAttributeInitialValues.length + "' is not equal for '" +
                    attributeFunction + "'");
        }
        return incrementalAttributeAggregator;
    }

    private static ExpressionExecutor getTimestampExecutor(AggregationDefinition aggregationDefinition,
                                                           SiddhiAppContext siddhiAppContext,
                                                           Map<String, Table> tableMap,
                                                           List<VariableExpressionExecutor> variableExpressionExecutors,
                                                           String aggregatorName, MetaStreamEvent metaStreamEvent) {
        // Retrieve the external timestamp. If not given, this would return null.
        Expression timestampExpression = aggregationDefinition.getAggregateAttribute();
        if (timestampExpression == null) {
            timestampExpression = AttributeFunction.function("currentTimeMillis",null);
        }
        ExpressionExecutor timestampExecutor = ExpressionParser.parseExpression(timestampExpression,
                metaStreamEvent, 0, tableMap, variableExpressionExecutors,
                siddhiAppContext, false, 0, aggregatorName);
        if (timestampExecutor.getReturnType() != Attribute.Type.LONG) {
            throw new SiddhiAppCreationException(
                    "AggregationDefinition '" + aggregationDefinition.getId() + "'s aggregateAttribute does not" +
                            " return long, but returns " + timestampExecutor.getReturnType() + ". " +
                            "Hence, can't create the siddhi app '" + siddhiAppContext.getName() + "'");
        }
        return timestampExecutor;
    }

    private static boolean isRange(TimePeriod timePeriod) {
        if (timePeriod.getOperator() == TimePeriod.Operator.RANGE) {
            return true;
        }
        return false;
    }

    private static List<TimePeriod.Duration> getSortedPeriods(TimePeriod timePeriod) {
        List<TimePeriod.Duration> durations = timePeriod.getDurations();
        if (isRange(timePeriod)) {
            durations = fillGap(durations.get(0), durations.get(1));
        }
        return sortedDurations(durations);
    }

    private static List<TimePeriod.Duration> sortedDurations(List<TimePeriod.Duration> durations) {
        List<TimePeriod.Duration> copyDurations = new ArrayList<TimePeriod.Duration>(durations);

        Comparator periodComparator = new Comparator<TimePeriod.Duration>() {
            public int compare(TimePeriod.Duration firstDuration, TimePeriod.Duration secondDuration) {
                int firstOrdinal = firstDuration.ordinal();
                int secondOrdinal = secondDuration.ordinal();
                if (firstOrdinal > secondOrdinal) {
                    return 1;
                } else if (firstOrdinal < secondOrdinal) {
                    return -1;
                }
                return 0;
            }
        };
        Collections.sort(copyDurations, periodComparator);
        return copyDurations;
    }

    private static List<TimePeriod.Duration> fillGap(TimePeriod.Duration start, TimePeriod.Duration end) {
        TimePeriod.Duration[] durations = TimePeriod.Duration.values();
        List<TimePeriod.Duration> filledDurations = new ArrayList<>();

        int startIndex = start.ordinal();
        int endIndex = end.ordinal();

        if (startIndex > endIndex) {
            throw new SiddhiAppCreationException(
                    "Start time period must be less than end time period for range aggregation calculation");
        }

        if (startIndex == endIndex) {

            filledDurations.add(start);

        } else {
            TimePeriod.Duration[] temp = new TimePeriod.Duration[endIndex - startIndex + 1];
            System.arraycopy(durations, startIndex, temp, 0, endIndex - startIndex + 1);
            filledDurations = Arrays.asList(temp);
        }
        return filledDurations;
    }

    private static void initDefaultTables(Map<String, Table> tableMap, String aggregatorName,
                                          List<TimePeriod.Duration> durations, MetaStreamEvent newMeta,
                                          SiddhiAppContext siddhiAppContext) {
        for (TimePeriod.Duration duration : durations) {
            TableDefinition tableDefinition = TableDefinition.id(aggregatorName + "_" + duration.toString());
            MetaStreamEvent tableMetaStreamEvent = new MetaStreamEvent();
            for (Attribute attribute : newMeta.getOnAfterWindowData()) {
                tableDefinition.attribute(attribute.getName(), attribute.getType());
                tableMetaStreamEvent.addOutputData(attribute); // A new meta needs to be created since
                // value mapping is done based on output data in in-memory tables. newMeta has no output data.
            }

            tableMetaStreamEvent.addInputDefinition(tableDefinition);

            StreamEventPool tableStreamEventPool = new StreamEventPool(tableMetaStreamEvent, 10);
            StreamEventCloner tableStreamEventCloner = new StreamEventCloner(tableMetaStreamEvent,
                    tableStreamEventPool);
            ConfigReader configReader = null;
            InMemoryTable inMemoryTable = new InMemoryTable();
            inMemoryTable.init(tableDefinition, tableStreamEventPool, tableStreamEventCloner, configReader,
                    siddhiAppContext);
            tableMap.putIfAbsent(tableDefinition.getId(), inMemoryTable);
        }
    }

//    public static class ExpressionExecutorDetails {
//        private ExpressionExecutor executor;
//        private String executorName;
//
//        public ExpressionExecutorDetails(ExpressionExecutor executor, String executorName) {
//            this.executor = executor;
//            this.executorName = executorName;
//        }
//
//        public ExpressionExecutor getExecutor() {
//            return this.executor;
//        }
//
//        public String getExecutorName() {
//            return this.executorName;
//        }
//
//        public ExpressionExecutorDetails copy() {
//            return new ExpressionExecutorDetails(executor, executorName);
//        }
//    }
}
