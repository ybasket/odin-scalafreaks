/*
 * Copyright 2024 ScalaFreaks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.odin.loggers

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import java.util.UUID

import io.odin.{fileLogger, Logger, LoggerMessage}
import io.odin.formatter.options.{PositionFormat, ThrowableFormat}
import io.odin.formatter.Formatter
import io.odin.json.Formatter as JsonFormatter
import io.odin.meta.Position
import io.odin.syntax.*

import cats.effect.unsafe.IORuntime
import cats.effect.IO
import cats.syntax.all.*
import cats.Eval
import io.odin
import org.apache.logging.log4j.LogManager
import org.openjdk.jmh.annotations.*
import scribe.file.*
import scribe.mdc.MDC
import scribe.Logger as ScribeLogger

// $COVERAGE-OFF$
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(warmups = 2, jvmArgsAppend = Array("-XX:MaxInlineLevel=18", "-XX:MaxInlineSize=270", "-XX:MaxTrivialSize=12"))
abstract class OdinBenchmarks {

  val message: String              = "msg"
  val contextKey: String           = "hello"
  val contextValue: String         = "world"
  val context: Map[String, String] = Map(contextKey -> contextValue)
  val throwable                    = new Error()

  val loggerMessage: LoggerMessage = LoggerMessage(
    io.odin.Level.Debug,
    Eval.later(message),
    context,
    Some(throwable),
    Position(
      "foobar",
      "foo/bar/foobar.scala",
      "io.odin.foobar",
      100
    ),
    "just-a-test-thread",
    1574716305L
  )

  implicit val ioRuntime: IORuntime = IORuntime.global

}

@State(Scope.Benchmark)
class DefaultLoggerBenchmarks extends OdinBenchmarks {

  val noop: Logger[IO] = Logger.noop

  val defaultLogger: Logger[IO] = new DefaultLogger[IO](io.odin.Level.Trace) {
    def submit(msg: LoggerMessage): IO[Unit] = noop.log(msg)

    def withMinimalLevel(level: odin.Level): Logger[IO] = this
  }

  @Benchmark
  def ignored(): Unit = defaultLogger.trace(message).unsafeRunSync()

  @Benchmark
  def msg(): Unit = defaultLogger.info(message).unsafeRunSync()

  @Benchmark
  def msgAndCtx(): Unit = defaultLogger.info(message, context).unsafeRunSync()

  @Benchmark
  def msgCtxThrowable(): Unit = defaultLogger.info(message, context, throwable).unsafeRunSync()

}

@State(Scope.Benchmark)
class FileLoggerBenchmarks extends OdinBenchmarks {

  val fileName: String = Files.createTempFile(UUID.randomUUID().toString, "").toAbsolutePath.toString

  val (logger: Logger[IO], cancelToken: IO[Unit]) =
    fileLogger[IO](fileName).allocated.unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msg(): Unit =
    (1 to 1000).toList.traverse_(_ => logger.info(message)).unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msgAndCtx(): Unit =
    (1 to 1000).toList.traverse_(_ => logger.info(message, context)).unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msgCtxThrowable(): Unit =
    (1 to 1000).toList.traverse_(_ => logger.info(message, context, throwable)).unsafeRunSync()

  @TearDown
  def tearDown(): Unit = {
    cancelToken.unsafeRunSync()
    Files.delete(Paths.get(fileName))
  }

}

@State(Scope.Benchmark)
class Log4jBenchmark extends OdinBenchmarks {

  private val logger        = LogManager.getLogger("log4j")
  private val tracingLogger = LogManager.getLogger("log4j-trace")
  private val asyncLogger   = LogManager.getLogger("log4j-async")

  @Benchmark
  @OperationsPerInvocation(1000)
  def msg(): Unit =
    for (_ <- 1 to 1000) logger.info(message)

  @Benchmark
  @OperationsPerInvocation(1000)
  def tracedMsg(): Unit =
    for (_ <- 1 to 1000) tracingLogger.info(message)

  @Benchmark
  @OperationsPerInvocation(1000)
  def asyncMsg(): Unit =
    for (_ <- 1 to 1000) asyncLogger.info(message)

  @TearDown
  def tearDown(): Unit = {
    Files.delete(Paths.get("log4j.log"))
    Files.delete(Paths.get("log4j-trace.log"))
    Files.delete(Paths.get("log4j-async.log"))
  }

}

@State(Scope.Benchmark)
class ScribeBenchmark extends OdinBenchmarks {

  private val writer      = FileWriter("scribe.log")
  private val asyncWriter = FileWriter("scribe-async.log")

  private val logger =
    ScribeLogger.empty.orphan().withHandler(writer = writer)

  private val asyncLogger = ScribeLogger.empty.orphan().withHandler(scribe.format.Formatter.default, asyncWriter)

  @Benchmark
  @OperationsPerInvocation(1000)
  def msg(): Unit =
    for (_ <- 1 to 1000) logger.info(message)

  @Benchmark
  @OperationsPerInvocation(1000)
  def msgAndCtx(): Unit =
    for (_ <- 1 to 1000) {
      MDC(contextKey) = contextValue
      logger.info(message)
    }

  @Benchmark
  @OperationsPerInvocation(1000)
  def asyncMsg(): Unit =
    for (_ <- 1 to 1000) asyncLogger.info(message)

  @Benchmark
  @OperationsPerInvocation(1000)
  def asyncMsgCtx(): Unit =
    for (_ <- 1 to 1000) {
      MDC(contextKey) = contextValue
      asyncLogger.info(message)
    }

  @TearDown
  def tearDown(): Unit = {
    writer.dispose()
    asyncWriter.dispose()
  }

}

@State(Scope.Benchmark)
class AsyncLoggerBenchmark extends OdinBenchmarks {

  val fileName: String = Files.createTempFile(UUID.randomUUID().toString, "").toAbsolutePath.toString

  val (asyncLogger: Logger[IO], cancelToken: IO[Unit]) =
    fileLogger[IO](fileName).withAsync(maxBufferSize = Some(1000000)).allocated.unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msg(): Unit = (1 to 1000).toList.traverse_(_ => asyncLogger.info(message)).unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msgAndCtx(): Unit =
    (1 to 1000).toList.traverse_(_ => asyncLogger.info(message, context)).unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msgCtxThrowable(): Unit =
    (1 to 1000).toList.traverse_(_ => asyncLogger.info(message, context, throwable)).unsafeRunSync()

  @TearDown
  def tearDown(): Unit = {
    cancelToken.unsafeRunSync()
    Files.delete(Paths.get(fileName))
  }

}

@State(Scope.Benchmark)
class AsyncLoggerDrainBenchmark extends OdinBenchmarks {

  val fileName: String = Files.createTempFile(UUID.randomUUID().toString, "").toAbsolutePath.toString

  val (asyncLogger: AsyncLogger[IO], cancelToken: IO[Unit]) =
    fileLogger[IO](fileName)
      .withAsync(maxBufferSize = Some(1000000))
      .map(_.asInstanceOf[AsyncLogger[IO]])
      .allocated
      .unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msg(): Unit = asyncLogger.info(message).replicateA_(1000).productR(asyncLogger.drain(None)).unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msgAndCtx(): Unit =
    asyncLogger.info(message, context).replicateA_(1000).productR(asyncLogger.drain(None)).unsafeRunSync()

  @Benchmark
  @OperationsPerInvocation(1000)
  def msgCtxThrowable(): Unit =
    asyncLogger.info(message, context, throwable).replicateA_(1000).productR(asyncLogger.drain(None)).unsafeRunSync()

  @TearDown
  def tearDown(): Unit = {
    cancelToken.unsafeRunSync()
    Files.delete(Paths.get(fileName))
  }

}

@State(Scope.Benchmark)
class FormatterBenchmarks extends OdinBenchmarks {

  private val noCtx: LoggerMessage       = loggerMessage.copy(context = Map.empty)
  private val noThrowable: LoggerMessage = noCtx.copy(exception = None)

  private val formatterDepth = Formatter.create(
    ThrowableFormat(ThrowableFormat.Depth.Fixed(2), ThrowableFormat.Indent.NoIndent, ThrowableFormat.Filter.NoFilter),
    PositionFormat.Full,
    colorful = false,
    printCtx = true
  )

  private val formatterDepthIndent = Formatter.create(
    ThrowableFormat(ThrowableFormat.Depth.Fixed(2), ThrowableFormat.Indent.Fixed(4), ThrowableFormat.Filter.NoFilter),
    PositionFormat.Full,
    colorful = false,
    printCtx = true
  )

  private val formatterDepthIndentFilter = Formatter.create(
    ThrowableFormat(
      ThrowableFormat.Depth.Fixed(2),
      ThrowableFormat.Indent.Fixed(4),
      ThrowableFormat.Filter.Excluding("cats.effect.IOApp", "io.odin.OdinBenchmarks", "io.odin.FormatterBenchmarks")
    ),
    PositionFormat.Full,
    colorful = false,
    printCtx = true
  )

  private val abbreviated = Formatter.create(
    ThrowableFormat.Default,
    PositionFormat.AbbreviatePackage,
    colorful = false,
    printCtx = true
  )

  @Benchmark
  def defaultFormatter(): String = Formatter.default.format(loggerMessage)

  @Benchmark
  def defaultColorful(): String = Formatter.colorful.format(loggerMessage)

  @Benchmark
  def defaultFormatterNoCtx(): String = Formatter.default.format(noCtx)

  @Benchmark
  def defaultFormatterNoCtxThrowable(): String = Formatter.default.format(noThrowable)

  @Benchmark
  def jsonFormatter(): String = JsonFormatter.json.format(loggerMessage)

  @Benchmark
  def depthFormatter(): String = formatterDepth.format(loggerMessage)

  @Benchmark
  def depthIndentFormatter(): String = formatterDepthIndent.format(loggerMessage)

  @Benchmark
  def depthIndentFilterFormatter(): String = formatterDepthIndentFilter.format(loggerMessage)

  @Benchmark
  def abbreviatedPositionFormatter(): String = abbreviated.format(loggerMessage)

}

@State(Scope.Benchmark)
class PositionBenchmark extends OdinBenchmarks {

  @Benchmark
  def resolve(): String = implicitly[Position].enclosureName

}

// $COVERAGE-ON$