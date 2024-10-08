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

package org.apache.flink.sql.parser.utils;

import org.apache.flink.sql.parser.impl.ParseException;

import org.apache.calcite.runtime.Resources;

/** Compiler-checked resources for the Flink SQL parser. */
public interface ParserResource {

    /** Resources. */
    ParserResource RESOURCE = Resources.create(ParserResource.class);

    @Resources.BaseMessage("Multiple WATERMARK statements is not supported yet.")
    Resources.ExInst<ParseException> multipleWatermarksUnsupported();

    @Resources.BaseMessage("OVERWRITE expression is only used with INSERT statement.")
    Resources.ExInst<ParseException> overwriteIsOnlyUsedWithInsert();

    @Resources.BaseMessage(
            "CREATE SYSTEM FUNCTION is not supported, system functions can only be registered as temporary function, you can use CREATE TEMPORARY SYSTEM FUNCTION instead.")
    Resources.ExInst<ParseException> createSystemFunctionOnlySupportTemporary();

    @Resources.BaseMessage("Duplicate EXPLAIN DETAIL is not allowed.")
    Resources.ExInst<ParseException> explainDetailIsDuplicate();

    @Resources.BaseMessage(
            "Unsupported CREATE OR REPLACE statement for EXPLAIN. The statement must define a query using the AS clause (i.e. CTAS/RTAS statements).")
    Resources.ExInst<ParseException> explainCreateOrReplaceStatementUnsupported();

    @Resources.BaseMessage(
            "Columns identifiers without types in the schema are supported on CTAS/RTAS statements only.")
    Resources.ExInst<ParseException> columnsIdentifiersUnsupported();

    @Resources.BaseMessage("CREATE FUNCTION USING JAR syntax is not applicable to {0} language.")
    Resources.ExInst<ParseException> createFunctionUsingJar(String language);

    @Resources.BaseMessage("WITH DRAIN could only be used after WITH SAVEPOINT.")
    Resources.ExInst<ParseException> withDrainOnlyUsedWithSavepoint();

    @Resources.BaseMessage("Bucket count must be a positive integer.")
    Resources.ExInst<ParseException> bucketCountMustBePositiveInteger();

    @Resources.BaseMessage(
            "MATERIALIZED TABLE only supports define interval type FRESHNESS, please refer to the materialized table document.")
    Resources.ExInst<ParseException> unsupportedFreshnessType();

    @Resources.BaseMessage("CREATE TEMPORARY MATERIALIZED TABLE is not supported.")
    Resources.ExInst<ParseException> createTemporaryMaterializedTableUnsupported();

    @Resources.BaseMessage("REPLACE MATERIALIZED TABLE is not supported.")
    Resources.ExInst<ParseException> replaceMaterializedTableUnsupported();

    @Resources.BaseMessage("DROP TEMPORARY MATERIALIZED TABLE is not supported.")
    Resources.ExInst<ParseException> dropTemporaryMaterializedTableUnsupported();
}
