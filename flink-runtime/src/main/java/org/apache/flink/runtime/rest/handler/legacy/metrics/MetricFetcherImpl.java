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

package org.apache.flink.runtime.rest.handler.legacy.metrics;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;
import org.apache.flink.configuration.WebOptions;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.messages.webmonitor.JobDetails;
import org.apache.flink.runtime.messages.webmonitor.MultipleJobsDetails;
import org.apache.flink.runtime.metrics.dump.MetricDumpSerialization;
import org.apache.flink.runtime.webmonitor.RestfulGateway;
import org.apache.flink.runtime.webmonitor.retriever.GatewayRetriever;
import org.apache.flink.runtime.webmonitor.retriever.MetricQueryServiceGateway;
import org.apache.flink.runtime.webmonitor.retriever.MetricQueryServiceRetriever;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.concurrent.FutureUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.apache.flink.runtime.metrics.dump.MetricDumpSerialization.MetricDumpDeserializer;

/**
 * Implementation of {@link MetricFetcher} which fetches metrics from the {@link
 * MetricQueryServiceGateway}.
 *
 * @param <T> type of the {@link RestfulGateway} from which to retrieve the metric query service
 *     path.
 */
public class MetricFetcherImpl<T extends RestfulGateway> implements MetricFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(MetricFetcherImpl.class);

    private final GatewayRetriever<T> retriever;
    private final MetricQueryServiceRetriever queryServiceRetriever;
    private final Executor executor;
    private final Duration timeout;

    private final MetricStore metrics = new MetricStore();
    private final MetricDumpDeserializer deserializer = new MetricDumpDeserializer();
    private final long updateInterval;

    @GuardedBy("this")
    private long lastUpdateTime;

    @GuardedBy("this")
    private CompletableFuture<Void> fetchMetricsFuture = FutureUtils.completedVoidFuture();

    public MetricFetcherImpl(
            GatewayRetriever<T> retriever,
            MetricQueryServiceRetriever queryServiceRetriever,
            Executor executor,
            Duration timeout,
            long updateInterval) {
        this.retriever = Preconditions.checkNotNull(retriever);
        this.queryServiceRetriever = Preconditions.checkNotNull(queryServiceRetriever);
        this.executor = Preconditions.checkNotNull(executor);
        this.timeout = Preconditions.checkNotNull(timeout);

        Preconditions.checkArgument(
                updateInterval > 0, "The update interval must be larger than 0.");
        this.updateInterval = updateInterval;
    }

    /**
     * Returns the MetricStore containing all stored metrics.
     *
     * @return MetricStore containing all stored metrics;
     */
    @Override
    public MetricStore getMetricStore() {
        return metrics;
    }

    /**
     * This method can be used to signal this MetricFetcher that the metrics are still in use and
     * should be updated.
     */
    @Override
    public void update() {
        synchronized (this) {
            long currentTime = System.currentTimeMillis();
            // Before all querying metric tasks are completed, new metric updating tasks cannot
            // be added. This is to avoid resource waste or other problems, such as OOM, caused by
            // adding too many useless querying tasks. See FLINK-29134.
            if (currentTime - lastUpdateTime > updateInterval && fetchMetricsFuture.isDone()) {
                lastUpdateTime = currentTime;
                fetchMetricsFuture = fetchMetrics();
            }
        }
    }

    @Override
    public long getLastUpdateTime() {
        synchronized (this) {
            return lastUpdateTime;
        }
    }

    private CompletableFuture<Void> fetchMetrics() {
        LOG.debug("Start fetching metrics.");

        try {
            Optional<T> optionalLeaderGateway = retriever.getNow();
            if (optionalLeaderGateway.isPresent()) {
                final T leaderGateway = optionalLeaderGateway.get();

                /*
                 * Remove all metrics that belong to a job that is not running and no longer archived.
                 */
                CompletableFuture<MultipleJobsDetails> jobDetailsFuture =
                        leaderGateway.requestMultipleJobDetails(timeout);

                jobDetailsFuture.whenCompleteAsync(
                        (MultipleJobsDetails jobDetails, Throwable throwable) -> {
                            if (throwable != null) {
                                LOG.debug("Fetching of JobDetails failed.", throwable);
                            } else {
                                ArrayList<String> toRetain =
                                        new ArrayList<>(jobDetails.getJobs().size());
                                for (JobDetails job : jobDetails.getJobs()) {
                                    toRetain.add(job.getJobId().toString());
                                }
                                metrics.retainJobs(toRetain);
                                metrics.updateCurrentExecutionAttempts(jobDetails.getJobs());
                            }
                        },
                        executor);

                List<CompletableFuture<Void>> waitingMetricsFutures = new ArrayList<>();
                CompletableFuture<Void> jmMetricsFuture = queryJmMetricsFuture(leaderGateway);
                waitingMetricsFutures.add(jmMetricsFuture);
                jmMetricsFuture.whenCompleteAsync(
                        (ignore, throwable) -> {
                            if (throwable != null) {
                                LOG.debug("Failed to fetch the leader's metrics.", throwable);
                            }
                        },
                        executor);
                CompletableFuture<Void> tmMetricsFuture = queryTmMetricsFuture(leaderGateway);
                waitingMetricsFutures.add(tmMetricsFuture);
                tmMetricsFuture.whenCompleteAsync(
                        (ignore, throwable) -> {
                            if (throwable != null) {
                                LOG.debug("Failed to fetch the TaskManager's metrics.", throwable);
                            }
                        },
                        executor);
                return FutureUtils.waitForAll(waitingMetricsFutures);
            }
        } catch (Exception e) {
            LOG.debug("Exception while fetching metrics.", e);
            return FutureUtils.completedExceptionally(e);
        }
        return FutureUtils.completedVoidFuture();
    }

    private CompletableFuture<Void> queryJmMetricsFuture(T leaderGateway) {
        CompletableFuture<Collection<String>> queryServiceAddressesFuture =
                leaderGateway.requestMetricQueryServiceAddresses(timeout);
        return queryServiceAddressesFuture.thenComposeAsync(
                (Collection<String> queryServiceAddresses) -> {
                    List<CompletableFuture<Void>> queryMetricFutures = new ArrayList<>();
                    for (String queryServiceAddress : queryServiceAddresses) {
                        queryMetricFutures.add(retrieveAndQueryMetrics(queryServiceAddress));
                    }
                    return FutureUtils.waitForAll(queryMetricFutures);
                },
                executor);
    }

    private CompletableFuture<Void> queryTmMetricsFuture(T leaderGateway) {
        // TODO: Once the old code has been ditched, remove the explicit TaskManager query
        // service discovery
        // TODO: and return it as part of requestMetricQueryServiceAddresses. Moreover,
        // change the MetricStore such that
        // TODO: we don't have to explicitly retain the valid TaskManagers, e.g. letting it
        // be a cache with expiry time
        CompletableFuture<Collection<Tuple2<ResourceID, String>>>
                taskManagerQueryServiceGatewaysFuture =
                        leaderGateway.requestTaskManagerMetricQueryServiceAddresses(timeout);
        return taskManagerQueryServiceGatewaysFuture.thenComposeAsync(
                (Collection<Tuple2<ResourceID, String>> queryServiceGateways) -> {
                    List<CompletableFuture<Void>> queryMetricFutures = new ArrayList<>();
                    List<String> taskManagersToRetain =
                            queryServiceGateways.stream()
                                    .map(
                                            (Tuple2<ResourceID, String> tuple) -> {
                                                queryMetricFutures.add(
                                                        queryServiceRetriever
                                                                .retrieveService(tuple.f1)
                                                                .thenComposeAsync(
                                                                        this::queryMetrics,
                                                                        executor));
                                                return tuple.f0.getResourceIdString();
                                            })
                                    .collect(Collectors.toList());

                    metrics.retainTaskManagers(taskManagersToRetain);
                    return FutureUtils.waitForAll(queryMetricFutures);
                },
                executor);
    }

    /**
     * Retrieves and queries the specified QueryServiceGateway.
     *
     * @param queryServiceAddress specifying the QueryServiceGateway
     */
    private CompletableFuture<Void> retrieveAndQueryMetrics(String queryServiceAddress) {
        LOG.debug("Retrieve metric query service gateway for {}", queryServiceAddress);

        final CompletableFuture<MetricQueryServiceGateway> queryServiceGatewayFuture =
                queryServiceRetriever.retrieveService(queryServiceAddress);
        return queryServiceGatewayFuture.thenComposeAsync(this::queryMetrics, executor);
    }

    /**
     * Query the metrics from the given QueryServiceGateway.
     *
     * @param queryServiceGateway to query for metrics
     */
    private CompletableFuture<Void> queryMetrics(
            final MetricQueryServiceGateway queryServiceGateway) {
        LOG.debug("Query metrics for {}.", queryServiceGateway.getAddress());

        return queryServiceGateway
                .queryMetrics(timeout)
                .thenComposeAsync(
                        (MetricDumpSerialization.MetricSerializationResult result) -> {
                            metrics.addAll(deserializer.deserialize(result));
                            return FutureUtils.completedVoidFuture();
                        },
                        executor);
    }

    @Nonnull
    public static <T extends RestfulGateway> MetricFetcherImpl<T> fromConfiguration(
            final Configuration configuration,
            final MetricQueryServiceRetriever metricQueryServiceGatewayRetriever,
            final GatewayRetriever<T> dispatcherGatewayRetriever,
            final ExecutorService executor) {
        final Duration timeout = configuration.get(WebOptions.TIMEOUT);
        final long updateInterval =
                configuration.get(MetricOptions.METRIC_FETCHER_UPDATE_INTERVAL).toMillis();

        return new MetricFetcherImpl<>(
                dispatcherGatewayRetriever,
                metricQueryServiceGatewayRetriever,
                executor,
                timeout,
                updateInterval);
    }
}
