/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.flink.test.example.client;

import org.apache.flink.client.deployment.executors.LocalExecutor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.legacy.OutputFormatSinkFunction;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.legacy.io.TextInputFormat;
import org.apache.flink.streaming.api.legacy.io.TextOutputFormat;
import org.apache.flink.test.testdata.WordCountData;
import org.apache.flink.test.testfunctions.Tokenizer;
import org.apache.flink.util.TestLogger;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;

import static org.apache.flink.core.testutils.CommonTestUtils.assertThrows;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/** Integration tests for {@link LocalExecutor}. */
public class LocalExecutorITCase extends TestLogger {

    private static final int parallelism = 4;

    private MiniCluster miniCluster;
    private LocalExecutor executor;

    @Before
    public void before() {
        executor =
                LocalExecutor.createWithFactory(
                        new Configuration(),
                        config -> {
                            miniCluster = new MiniCluster(config);
                            return miniCluster;
                        });
    }

    @Test(timeout = 60_000)
    public void testLocalExecutorWithWordCount() throws InterruptedException {
        try {
            // set up the files
            File inFile = File.createTempFile("wctext", ".in");
            File outFile = File.createTempFile("wctext", ".out");
            inFile.deleteOnExit();
            outFile.deleteOnExit();

            try (FileWriter fw = new FileWriter(inFile)) {
                fw.write(WordCountData.TEXT);
            }

            final Configuration config = new Configuration();
            config.set(CoreOptions.FILESYTEM_DEFAULT_OVERRIDE, true);
            config.set(DeploymentOptions.ATTACHED, true);

            StreamGraph wcStreamGraph = getWordCountStreamGraph(inFile, outFile, parallelism);
            JobClient jobClient =
                    executor.execute(wcStreamGraph, config, ClassLoader.getSystemClassLoader())
                            .get();
            jobClient.getJobExecutionResult().get();
        } catch (Exception e) {
            e.printStackTrace();
            Assert.fail(e.getMessage());
        }

        assertThat(miniCluster.isRunning(), is(false));
    }

    @Test(timeout = 60_000)
    public void testMiniClusterShutdownOnErrors() throws Exception {
        StreamGraph runtimeExceptionPlan = getRuntimeExceptionPlan();

        Configuration config = new Configuration();
        config.set(DeploymentOptions.ATTACHED, true);

        JobClient jobClient =
                executor.execute(runtimeExceptionPlan, config, ClassLoader.getSystemClassLoader())
                        .get();

        assertThrows(
                "Job execution failed.",
                Exception.class,
                () -> jobClient.getJobExecutionResult().get());

        assertThat(miniCluster.isRunning(), is(false));
    }

    private StreamGraph getWordCountStreamGraph(File inFile, File outFile, int parallelism) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(parallelism);
        env.createInput(new TextInputFormat(new Path(inFile.getAbsolutePath())))
                .flatMap(new Tokenizer())
                .keyBy(x -> x.f0)
                .sum(1)
                .addSink(
                        new OutputFormatSinkFunction<>(
                                new TextOutputFormat<>(new Path(outFile.getAbsolutePath()))));
        return env.getStreamGraph();
    }

    private StreamGraph getRuntimeExceptionPlan() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.fromData(1)
                .map(
                        element -> {
                            if (element == 1) {
                                throw new RuntimeException("oups");
                            }
                            return element;
                        })
                .sinkTo(new DiscardingSink<>());
        return env.getStreamGraph();
    }
}
