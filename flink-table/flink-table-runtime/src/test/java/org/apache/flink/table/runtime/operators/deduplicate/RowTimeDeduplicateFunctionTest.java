/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.runtime.operators.deduplicate;

import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.util.OneInputStreamOperatorTestHarness;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.operators.bundle.KeyedMapBundleOperator;
import org.apache.flink.table.runtime.operators.bundle.trigger.CountBundleTrigger;
import org.apache.flink.testutils.junit.extensions.parameterized.ParameterizedTestExtension;
import org.apache.flink.testutils.junit.extensions.parameterized.Parameters;
import org.apache.flink.types.RowKind;

import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.apache.flink.table.runtime.util.StreamRecordUtils.insertRecord;
import static org.apache.flink.table.runtime.util.StreamRecordUtils.record;

/**
 * Harness tests for {@link RowTimeDeduplicateFunction} and {@link
 * RowTimeMiniBatchDeduplicateFunction}.
 */
@ExtendWith(ParameterizedTestExtension.class)
class RowTimeDeduplicateFunctionTest extends RowTimeDeduplicateFunctionTestBase {

    private final boolean miniBatchEnable;

    RowTimeDeduplicateFunctionTest(boolean miniBacthEnable) {
        this.miniBatchEnable = miniBacthEnable;
    }

    @Parameters(name = "miniBatchEnable = {0}")
    private static Collection<Boolean[]> runMode() {
        return Arrays.asList(new Boolean[] {false}, new Boolean[] {true});
    }

    @TestTemplate
    void testRowTimeDeduplicateKeepFirstRow() throws Exception {
        List<Object> expectedOutput = new ArrayList<>();
        expectedOutput.add(record(RowKind.INSERT, "key1", 13, 99L));
        expectedOutput.add(record(RowKind.INSERT, "key2", 11, 101L));
        expectedOutput.add(new Watermark(102));
        expectedOutput.add(record(RowKind.INSERT, "key3", 5, 299L));
        expectedOutput.add(new Watermark(302));
        expectedOutput.add(record(RowKind.INSERT, "key1", 12, 400L));
        expectedOutput.add(record(RowKind.INSERT, "key2", 11, 401L));
        expectedOutput.add(new Watermark(402));

        // generateUpdateBefore: true, generateInsert: true
        testRowTimeDeduplicateKeepFirstRow(true, true, expectedOutput);

        // generateUpdateBefore: true, generateInsert: false
        testRowTimeDeduplicateKeepFirstRow(true, false, expectedOutput);

        // generateUpdateBefore: false, generateInsert: true
        testRowTimeDeduplicateKeepFirstRow(false, true, expectedOutput);

        // generateUpdateBefore: false, generateInsert: false
        expectedOutput.clear();
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 13, 99L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key2", 11, 101L));
        expectedOutput.add(new Watermark(102));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key3", 5, 299L));
        expectedOutput.add(new Watermark(302));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 400L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key2", 11, 401L));
        expectedOutput.add(new Watermark(402));
        testRowTimeDeduplicateKeepFirstRow(false, false, expectedOutput);
    }

    @TestTemplate
    void testRowTimeDeduplicateKeepLastRow() throws Exception {
        List<Object> expectedOutput = new ArrayList<>();
        expectedOutput.add(record(RowKind.INSERT, "key1", 13, 99L));
        expectedOutput.add(record(RowKind.UPDATE_BEFORE, "key1", 13, 99L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 100L));
        expectedOutput.add(record(RowKind.INSERT, "key2", 11, 101L));
        expectedOutput.add(new Watermark(102));
        expectedOutput.add(record(RowKind.UPDATE_BEFORE, "key1", 12, 100L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 300L));
        expectedOutput.add(record(RowKind.UPDATE_BEFORE, "key2", 11, 101L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key2", 11, 301L));
        expectedOutput.add(record(RowKind.INSERT, "key3", 5, 299L));
        expectedOutput.add(new Watermark(302));
        expectedOutput.add(record(RowKind.INSERT, "key1", 12, 400L));
        expectedOutput.add(record(RowKind.INSERT, "key2", 11, 401L));
        expectedOutput.add(new Watermark(402));

        // generateUpdateBefore: true, generateInsert: true
        testRowTimeDeduplicateKeepLastRow(true, true, expectedOutput);

        // generateUpdateBefore: true, generateInsert: false
        testRowTimeDeduplicateKeepLastRow(true, false, expectedOutput);

        // generateUpdateBefore: false, generateInsert: true
        expectedOutput.clear();
        expectedOutput.add(record(RowKind.INSERT, "key1", 13, 99L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 100L));
        expectedOutput.add(record(RowKind.INSERT, "key2", 11, 101L));
        expectedOutput.add(new Watermark(102));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 300L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key2", 11, 301L));
        expectedOutput.add(record(RowKind.INSERT, "key3", 5, 299L));
        expectedOutput.add(new Watermark(302));
        expectedOutput.add(record(RowKind.INSERT, "key1", 12, 400L));
        expectedOutput.add(record(RowKind.INSERT, "key2", 11, 401L));
        expectedOutput.add(new Watermark(402));
        testRowTimeDeduplicateKeepLastRow(false, true, expectedOutput);

        // generateUpdateBefore: false, generateInsert: false
        expectedOutput.clear();
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 13, 99L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 100L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key2", 11, 101L));
        expectedOutput.add(new Watermark(102));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 300L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key2", 11, 301L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key3", 5, 299L));
        expectedOutput.add(new Watermark(302));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key1", 12, 400L));
        expectedOutput.add(record(RowKind.UPDATE_AFTER, "key2", 11, 401L));
        expectedOutput.add(new Watermark(402));
        testRowTimeDeduplicateKeepLastRow(false, false, expectedOutput);
    }

    private void testRowTimeDeduplicateKeepFirstRow(
            boolean generateUpdateBefore, boolean generateInsert, List<Object> expectedOutput)
            throws Exception {
        final boolean keepLastRow = false;
        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness;
        KeyedMapBundleOperator<RowData, RowData, RowData, RowData> keyedMapBundleOperator = null;
        KeyedProcessOperator keyedProcessOperator = null;
        if (miniBatchEnable) {
            RowTimeMiniBatchDeduplicateFunction func =
                    new RowTimeMiniBatchDeduplicateFunction(
                            inputRowType,
                            serializer,
                            minTtlTime.toMillis(),
                            rowTimeIndex,
                            generateUpdateBefore,
                            generateInsert,
                            keepLastRow);
            CountBundleTrigger trigger = new CountBundleTrigger<RowData>(miniBatchSize);
            keyedMapBundleOperator = new KeyedMapBundleOperator(func, trigger);
            testHarness = createTestHarness(keyedMapBundleOperator);
        } else {
            RowTimeDeduplicateFunction func =
                    new RowTimeDeduplicateFunction(
                            inputRowType,
                            minTtlTime.toMillis(),
                            rowTimeIndex,
                            generateUpdateBefore,
                            generateInsert,
                            keepLastRow);
            keyedProcessOperator = new KeyedProcessOperator<>(func);
            testHarness = createTestHarness(keyedProcessOperator);
        }

        List<Object> actualOutput = new ArrayList<>();
        testHarness.open();

        testHarness.processElement(insertRecord("key1", 13, 99L));
        testHarness.processElement(insertRecord("key1", 13, 99L));
        testHarness.processElement(insertRecord("key1", 12, 100L));
        testHarness.processElement(insertRecord("key2", 11, 101L));

        // test 1: keep first row with row time
        testHarness.processWatermark(new Watermark(102));
        actualOutput.addAll(testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();

        if (miniBatchEnable) {
            testHarness = createTestHarness(keyedMapBundleOperator);
        } else {
            testHarness = createTestHarness(keyedProcessOperator);
        }

        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.processElement(insertRecord("key1", 12, 300L));
        testHarness.processElement(insertRecord("key2", 11, 301L));
        testHarness.processElement(insertRecord("key3", 5, 299L));

        // test 2:  load snapshot state
        testHarness.processWatermark(new Watermark(302));

        // test 3: expire the state
        testHarness.setStateTtlProcessingTime(minTtlTime.toMillis() + 1);
        testHarness.processElement(insertRecord("key1", 12, 400L));
        testHarness.processElement(insertRecord("key2", 11, 401L));
        testHarness.processWatermark(402);

        // ("key1", 13, 99L) and ("key2", 11, 101L) had retired, thus output ("key1", 12,
        // 200L),("key2", 11, 201L)
        actualOutput.addAll(testHarness.getOutput());

        assertor.assertOutputEqualsSorted("output wrong.", expectedOutput, actualOutput);
        testHarness.close();
    }

    private void testRowTimeDeduplicateKeepLastRow(
            boolean generateUpdateBefore, boolean generateInsert, List<Object> expectedOutput)
            throws Exception {
        final boolean keepLastRow = true;
        OneInputStreamOperatorTestHarness<RowData, RowData> testHarness;
        KeyedMapBundleOperator<RowData, RowData, RowData, RowData> keyedMapBundleOperator = null;
        KeyedProcessOperator keyedProcessOperator = null;
        if (miniBatchEnable) {
            RowTimeMiniBatchDeduplicateFunction func =
                    new RowTimeMiniBatchDeduplicateFunction(
                            inputRowType,
                            serializer,
                            minTtlTime.toMillis(),
                            rowTimeIndex,
                            generateUpdateBefore,
                            generateInsert,
                            keepLastRow);
            CountBundleTrigger trigger = new CountBundleTrigger<RowData>(miniBatchSize);
            keyedMapBundleOperator = new KeyedMapBundleOperator(func, trigger);
            testHarness = createTestHarness(keyedMapBundleOperator);
        } else {
            RowTimeDeduplicateFunction func =
                    new RowTimeDeduplicateFunction(
                            inputRowType,
                            minTtlTime.toMillis(),
                            rowTimeIndex,
                            generateUpdateBefore,
                            generateInsert,
                            true);
            keyedProcessOperator = new KeyedProcessOperator<>(func);
            testHarness = createTestHarness(keyedProcessOperator);
        }

        List<Object> actualOutput = new ArrayList<>();
        testHarness.open();

        testHarness.processElement(insertRecord("key1", 13, 99L));
        testHarness.processElement(insertRecord("key1", 12, 100L));
        testHarness.processElement(insertRecord("key2", 11, 101L));

        // test 1: keep last row with row time
        testHarness.processWatermark(new Watermark(102));
        actualOutput.addAll(testHarness.getOutput());

        // do a snapshot, close and restore again
        OperatorSubtaskState snapshot = testHarness.snapshot(0L, 0);
        testHarness.close();

        if (miniBatchEnable) {
            testHarness = createTestHarness(keyedMapBundleOperator);
        } else {
            testHarness = createTestHarness(keyedProcessOperator);
        }

        testHarness.setup();
        testHarness.initializeState(snapshot);
        testHarness.open();

        testHarness.processElement(insertRecord("key1", 12, 300L));
        testHarness.processElement(insertRecord("key2", 11, 301L));
        testHarness.processElement(insertRecord("key3", 5, 299L));

        // test 2: load snapshot state
        testHarness.processWatermark(new Watermark(302));

        // test 3: expire the state
        testHarness.setStateTtlProcessingTime(minTtlTime.toMillis() + 1);
        testHarness.processElement(insertRecord("key1", 12, 400L));
        testHarness.processElement(insertRecord("key2", 11, 401L));
        testHarness.processWatermark(402);

        // all state has expired, so the record ("key1", 12, 400L), ("key2", 12, 401L) will be
        // INSERT message
        actualOutput.addAll(testHarness.getOutput());

        assertor.assertOutputEqualsSorted("output wrong.", expectedOutput, actualOutput);
        testHarness.close();
    }
}
