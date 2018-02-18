/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Copyright (c) 2015, Broad Institute, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name Broad Institute, Inc. nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE
 */

package ch.chuv.lren.woken.cromwell.core

import java.net.URL

import cats.data.ValidatedNel
import cats.syntax.validated._
import com.typesafe.config.{ Config, ConfigException }
import com.typesafe.scalalogging.Logger

import scala.collection.JavaConversions._
import scala.reflect.{ ClassTag, classTag }

object ConfigUtil {

  type Validation[A] = ValidatedNel[String, A]

  private val validationLogger = Logger("ConfigurationValidation")

  def lift[A](v: A): Validation[A] = v.validNel[String]

  def liftOption[A](v: Option[A]): Validation[A] =
    v.fold("No results".invalidNel[A])(_.validNel[String])

  implicit class EnhancedConfig(val config: Config) extends AnyVal {
    def keys: Set[String] = config.root().map(_._1).toSet

    /**
      * For keys that are in the configuration but not in the reference keySet, log a warning.
      */
    def warnNotRecognized(keySet: Set[String], context: String): Unit =
      keys.diff(keySet) match {
        case warnings if warnings.nonEmpty =>
          validationLogger.warn(
            s"Unrecognized configuration key(s) for $context: ${warnings.mkString(", ")}"
          )
        case _ =>
      }

    /**
      * Validates that the value for this key is a well formed URL.
      */
    def validateURL(key: String): Validation[URL] = key.validateAny { url =>
      new URL(config.getString(url))
    }

    def validateString(key: String): Validation[String] =
      try {
        config.getString(key).validNel
      } catch {
        case _: ConfigException.Missing => s"Could not find key: $key".invalidNel
      }

    def validateOptionalString(key: String): Validation[Option[String]] =
      try {
        config.getString(key).validNel.map(Some(_))
      } catch {
        case _: ConfigException.Missing => None.validNel
        case _: ConfigException         => s"Invalid value for key: $key".invalidNel
      }

    def validateStringList(key: String): Validation[List[String]] =
      try {
        config.getStringList(key).to[List].validNel
      } catch {
        case _: ConfigException.Missing => s"Could not find key: $key".invalidNel
      }

    def validateInt(key: String): Validation[Int] =
      try {
        config.getInt(key).validNel
      } catch {
        case _: ConfigException.Missing => s"Could not find key: $key".invalidNel
        case _: ConfigException.WrongType =>
          s"Invalid type for key: $key, expected an integer value".invalidNel
      }

    def validateBoolean(key: String): Validation[Boolean] =
      try {
        config.getBoolean(key).validNel
      } catch {
        case _: ConfigException.Missing => s"Could not find key: $key".invalidNel
        case _: ConfigException.WrongType =>
          s"Invalid type for key: $key, expected an integer value".invalidNel
      }

    def validateConfig(key: String): Validation[Config] =
      try {
        config.getConfig(key).validNel
      } catch {
        case _: ConfigException.Missing   => s"Could not find key: $key".invalidNel
        case _: ConfigException.WrongType => s"key $key cannot be parsed to a Config".invalidNel
      }

  }

  implicit class EnhancedValidation[I <: AnyRef](val value: I) extends AnyVal {

    /**
      * Validates this value by applying validationFunction to it and returning a Validation:
      * Returns successNel upon success.
      * If an exception is thrown and is a subtype of E, return failureNel with the exception message.
      * @param validationFunction function that should throw an exception if this value is found not to be valid
      * @tparam O return type of validationFunction
      * @tparam E Restricts the subtype of Exception that should be caught during validation
      */
    def validateAny[O, E <: Exception: ClassTag](
        validationFunction: I => O
    ): Validation[O] =
      try {
        validationFunction(value).validNel
      } catch {
        case e if classTag[E].runtimeClass.isInstance(e) => e.getMessage.invalidNel
      }
  }

}
