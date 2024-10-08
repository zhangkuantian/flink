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

package org.apache.flink.state.api;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.core.fs.Path;
import org.apache.flink.state.api.output.TaggedOperatorSubtaskState;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;

/** Creates a savepoint writing operator from a savepoint path. */
@PublicEvolving
@FunctionalInterface
public interface SavepointWriterOperatorFactory {

    /**
     * Creates a {@link StreamOperator} to be used for generating and snapshotting state.
     *
     * @param savepointTimestamp the timestamp to associate with the generated savepoint.
     * @param savepointPath the path to write the savepoint to.
     * @return a stream operator for writing the savepoint.
     */
    StreamOperatorFactory<TaggedOperatorSubtaskState> createOperator(
            long savepointTimestamp, Path savepointPath);
}
