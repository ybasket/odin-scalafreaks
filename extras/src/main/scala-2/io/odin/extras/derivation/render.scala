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

package io.odin.extras.derivation

import io.odin.meta.Render
import magnolia1._
import java.security.MessageDigest
import java.math.BigInteger

object render {

  type Typeclass[A] = Render[A]

  def join[A](ctx: CaseClass[Typeclass, A]): Typeclass[A] = value => {
    if (ctx.isValueClass) {
      ctx.parameters.headOption.fold("")(param => param.typeclass.render(param.dereference(value)))
    } else {
      val includeMemberNames = RenderUtils.includeMemberNames(ctx)

      val params = ctx.parameters
        .filterNot(RenderUtils.isHidden)
        .collect {
          case param if RenderUtils.isSecret(param) =>
            RenderUtils.renderParam(param.label, RenderUtils.SecretPlaceholder, includeMemberNames)

          case param if RenderUtils.shouldBeHashed(param) =>
            val label = s"${param.label} (sha256 hash)"
            val plaintext = param.typeclass.render(param.dereference(value))
            RenderUtils.renderParam(label, RenderUtils.sha256(plaintext), includeMemberNames)

          case RenderUtils.hasLengthLimit(param, limit) =>
            RenderUtils.renderWithLimit(param, value, limit, includeMemberNames)

          case p =>
            RenderUtils.renderParam(p.label, p.typeclass.render(p.dereference(value)), includeMemberNames)
        }

      s"${ctx.typeName.short}(${params.mkString(", ")})"
    }
  }

  def split[A](ctx: SealedTrait[Typeclass, A]): Typeclass[A] = value => {
    ctx.split(value)(sub => sub.typeclass.render(sub.cast(value)))
  }

  /**
    * Creates an instance for a concrete type. Does not generate instances for underlying types.
    *
    * Example:
    * {{{
    *   import io.odin.extras.derivation.render
    *
    *   case class A(field: String)
    *   case class B(field: A)
    *
    *   val instanceA: Render[A] = render.instance[A] // compiles
    *   val instanceB: Render[B] = render.instance[B] // does not compile
    * }}}
    */
  def instance[A]: Typeclass[A] = macro Magnolia.gen[A]

  /**
    * Creates an instance for a concrete type. Recursively generate instances for underlying types.
    *
    * Example:
    * {{{
    *   import io.odin.extras.derivation.render
    *
    *   case class A(field: String)
    *   case class B(field: A)
    *
    *   val instanceB: Render[B] = render.derive[B]
    * }}}
    */
  implicit def derive[A]: Typeclass[A] = macro Magnolia.gen[A]

}

private object RenderUtils {

  val SecretPlaceholder = "<secret>"

  @inline def includeMemberNames[A](ctx: CaseClass[Render, A]): Boolean =
    ctx.annotations
      .collectFirst { case rendered(v) => v }
      .getOrElse(true)

  @inline def isSecret[A](param: Param[Render, A]): Boolean =
    param.annotations.contains(secret())

  @inline def shouldBeHashed[A](param: Param[Render, A]): Boolean =
    param.annotations.contains(hash())

  @inline def isHidden[A](param: Param[Render, A]): Boolean =
    param.annotations.contains(hidden())

  @inline def renderParam(label: String, value: String, includeMemberName: Boolean): String =
    if (includeMemberName) s"$label = $value" else value

  def renderWithLimit[A](param: Param[Render, A], value: A, limit: Int, includeMemberNames: Boolean): String =
    param.dereference(value) match {
      case c: Iterable[_] =>
        val diff = c.iterator.length - limit
        val suffix = if (diff > 0) s"($diff more)" else ""
        val value = param.typeclass.render(c.take(limit).asInstanceOf[param.PType]) + suffix

        renderParam(param.label, value, includeMemberNames)

      case other =>
        renderParam(param.label, param.typeclass.render(other), includeMemberNames)
    }

  def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    String.format("%064x", new BigInteger(1, digest.digest()))
  }

  object hasLengthLimit {
    def unapply[A](arg: Param[Render, A]): Option[(Param[Render, A], Int)] =
      arg.annotations.collectFirst { case length(limit) => (arg, limit) }
  }

}
