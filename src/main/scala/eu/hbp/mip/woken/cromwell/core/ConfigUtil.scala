/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
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

package eu.hbp.mip.woken.cromwell.core

import java.net.URL

import cats.data.ValidatedNel
import cats.syntax.validated._
import com.typesafe.config.{ Config, ConfigException, ConfigValue }
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.reflect.{ ClassTag, classTag }

object ConfigUtil {

  type Validation[A] = ValidatedNel[String, A]

  private val validationLogger = LoggerFactory.getLogger("ConfigurationValidation")

  implicit class EnhancedConfig(val config: Config) extends AnyVal {
    def keys: Set[String] = config.entrySet().asScala.toSet map {
      v: java.util.Map.Entry[String, ConfigValue] =>
        v.getKey
    }

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
