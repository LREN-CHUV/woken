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

package eu.hbp.mip.woken.backends.chronos

import java.time.OffsetDateTime

import spray.json._

case class ChronosJobLiveliness(
    name: String,
    successCount: Int,
    errorCount: Int,
    lastSuccess: Option[OffsetDateTime],
    lastError: Option[OffsetDateTime],
    softError: Boolean,
    errorsSinceLastSuccess: Int,
    disabled: Boolean
    // "schedule": "R0/2017-11-29T23:13:40.989Z/PT1M"
)

/**
  * Deserialize job messages sent by Chronos as ChronosJobLiveliness
  */
object ChronosJobLiveliness extends DefaultJsonProtocol {

  implicit object OffsetDateTimeJsonFormat extends RootJsonFormat[Option[OffsetDateTime]] {
    override def write(x: Option[OffsetDateTime]) = throw new NotImplementedError()
    override def read(value: JsValue): Option[OffsetDateTime] = value match {
      case JsString("") => None
      case JsString(x) =>
        Some(OffsetDateTime.parse(x))
      case unknown =>
        deserializationError("Expected OffsetDateTime as JsNumber, but got " + unknown)
    }
  }

  implicit val chronosJobLivelinessFormat: RootJsonFormat[ChronosJobLiveliness] = jsonFormat8(
    ChronosJobLiveliness.apply
  )

}
