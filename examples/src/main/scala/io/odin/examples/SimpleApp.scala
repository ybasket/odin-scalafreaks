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

package io.odin.examples

import cats.Applicative
import cats.effect.{IO, IOApp}
import cats.syntax.all._
import io.odin._
import io.odin.formatter.Formatter

/**
  * This is a half of an example on how to easily test logged messages across the system. The test part is inside of
  * `https://github.com/scalafreaks/odin/tree/main/examples/src/test/scala/io/odin/examples/SimpleAppSpec.scala`.
  *
  * When application runs it prints out greeting along with log of the `simpleService.greet` call.
  */
object SimpleApp extends IOApp.Simple {

  private val logger: Logger[IO] = consoleLogger(formatter = Formatter.colorful)

  private val simpleService: SimpleService[IO] = new HelloSimpleService[IO](logger)

  def greetUser(name: String): IO[String] = simpleService.greet(name)

  def run: IO[Unit] = {
    greetUser("Viking").map(println(_))
  }
}

/**
  * Mind the polymorphic type `F` the service is defined for.
  *
  * It allows caller to decide what the actual effect type is used, and it's important for testing purposes
  */
trait SimpleService[F[_]] {

  def greet(name: String): F[String]

}

/**
  * Service logs the calls to its methods.
  *
  * It's completely okay to use the single logger instance across the whole application, as it saves some allocations
  * and simplifies the tests
  *
  * It's still polymorphic in effect type just as the interface it implements, but applies additional constraint
  * `Applicative[F]` for the sake of combining logging with the rest of the code
  */
class HelloSimpleService[F[_]](logger: Logger[F])(implicit F: Applicative[F]) extends SimpleService[F] {
  def greet(name: String): F[String] = {
    logger.debug(s"greet is called by user $name") *> F.pure(s"Hello $name")
  }
}
