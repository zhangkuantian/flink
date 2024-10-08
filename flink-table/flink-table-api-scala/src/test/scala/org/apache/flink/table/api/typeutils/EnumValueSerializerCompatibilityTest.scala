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
package org.apache.flink.table.api.typeutils

import org.apache.flink.api.common.typeutils.{TypeSerializerSchemaCompatibility, TypeSerializerSnapshotSerializationUtil}
import org.apache.flink.core.memory.{DataInputViewStreamWrapper, DataOutputViewStreamWrapper}

import org.assertj.core.api.Assertions.{assertThat, fail}
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.io._
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path}

import scala.reflect.NameTransformer
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.ConsoleReporter

class EnumValueSerializerCompatibilityTest {

  val enumName = "EnumValueSerializerUpgradeTestEnum"

  val enumA =
    s"""
       |object $enumName extends Enumeration {
       |  val A, B, C = Value
       |}
    """.stripMargin

  val enumB =
    s"""
       |object $enumName extends Enumeration {
       |  val A, B, C, D = Value
       |}
    """.stripMargin

  val enumC =
    s"""
       |object $enumName extends Enumeration {
       |  val A, C = Value
       |}
    """.stripMargin

  val enumD =
    s"""
       |object $enumName extends Enumeration {
       |  val A, C, B = Value
       |}
    """.stripMargin

  val enumE =
    s"""
       |object $enumName extends Enumeration {
       |  val A = Value(42)
       |  val B = Value(5)
       |  val C = Value(1337)
       |}
    """.stripMargin

  /** Check that identical enums don't require migration */
  @Test
  def checkIdenticalEnums(@TempDir tempFolder: Path): Unit = {
    assertThat(checkCompatibility(enumA, enumA, tempFolder).isCompatibleAsIs).isTrue
  }

  /** Check that appending fields to the enum does not require migration */
  @Test
  def checkAppendedField(@TempDir tempFolder: Path): Unit = {
    assertThat(checkCompatibility(enumA, enumB, tempFolder).isCompatibleAsIs).isTrue
  }

  /** Check that removing enum fields makes the snapshot incompatible. */
  @Test
  def checkRemovedField(@TempDir tempFolder: Path): Unit = {
    assertThat(checkCompatibility(enumA, enumC, tempFolder).isIncompatible).isTrue
  }

  /** Check that changing the enum field order makes the snapshot incompatible. */
  @Test
  def checkDifferentFieldOrder(@TempDir tempFolder: Path): Unit = {
    assertThat(checkCompatibility(enumA, enumD, tempFolder).isIncompatible).isTrue
  }

  /** Check that changing the enum ids causes a migration */
  @Test
  def checkDifferentIds(@TempDir tempFolder: Path): Unit = {
    assertThat(checkCompatibility(enumA, enumE, tempFolder).isIncompatible)
      .as("Different ids should be incompatible.")
      .isTrue
  }

  def checkCompatibility(
      enumSourceA: String,
      enumSourceB: String,
      tempFolder: Path): TypeSerializerSchemaCompatibility[Enumeration#Value] = {
    import EnumValueSerializerCompatibilityTest._

    val classLoader = compileAndLoadEnum(
      Files.createTempDirectory(tempFolder, "classLoader").toFile,
      s"$enumName.scala",
      enumSourceA)

    val enum = instantiateEnum[Enumeration](classLoader, enumName)

    val enumValueSerializer = new EnumValueSerializer(enum)
    val snapshot = enumValueSerializer.snapshotConfiguration()

    val baos = new ByteArrayOutputStream()
    val output = new DataOutputViewStreamWrapper(baos)
    TypeSerializerSnapshotSerializationUtil.writeSerializerSnapshot(output, snapshot)

    output.close()
    baos.close()

    val bais = new ByteArrayInputStream(baos.toByteArray)
    val input = new DataInputViewStreamWrapper(bais)

    val classLoader2 = compileAndLoadEnum(
      Files.createTempDirectory(tempFolder, "classLoader2").toFile,
      s"$enumName.scala",
      enumSourceB)

    val snapshot2 = TypeSerializerSnapshotSerializationUtil
      .readSerializerSnapshot[Enumeration#Value](input, classLoader2)
    val enum2 = instantiateEnum[Enumeration](classLoader2, enumName)

    val enumValueSerializer2 = new EnumValueSerializer(enum2)
    enumValueSerializer2.snapshotConfiguration().resolveSchemaCompatibility(snapshot2)
  }
}

object EnumValueSerializerCompatibilityTest {
  def compileAndLoadEnum(root: File, filename: String, source: String): ClassLoader = {
    val file = writeSourceFile(root, filename, source)

    compileScalaFile(file)

    new URLClassLoader(Array[URL](root.toURI.toURL), Thread.currentThread().getContextClassLoader)
  }

  def instantiateEnum[T <: Enumeration](classLoader: ClassLoader, enumName: String): T = {
    val clazz = classLoader.loadClass(enumName + "$").asInstanceOf[Class[_ <: Enumeration]]
    val field = clazz.getField(NameTransformer.MODULE_INSTANCE_NAME)

    field.get(null).asInstanceOf[T]
  }

  def writeSourceFile(root: File, filename: String, source: String): File = {
    val file = new File(root, filename)
    val fileWriter = new FileWriter(file)

    fileWriter.write(source)

    fileWriter.close()

    file
  }

  def compileScalaFile(file: File): Unit = {
    val settings = new Settings()

    // use the java classpath so that scala libraries are available to the compiler
    settings.usejavacp.value = true
    settings.outdir.value = file.getParent

    val reporter = new ConsoleReporter(settings)
    val global = new Global(settings, reporter)
    val run = new global.Run

    run.compile(List(file.getAbsolutePath))

    if (reporter.hasWarnings || reporter.hasErrors) {
      reporter.finish()
      fail("Scala compiler reported warnings or errors")
    }
  }
}
