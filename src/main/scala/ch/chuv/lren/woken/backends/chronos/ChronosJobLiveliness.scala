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

package ch.chuv.lren.woken.backends.chronos

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
        deserializationError(s"Expected OffsetDateTime as JsNumber, but got $unknown")
    }
  }

  implicit val chronosJobLivelinessFormat: RootJsonFormat[ChronosJobLiveliness] = jsonFormat8(
    ChronosJobLiveliness.apply
  )

}
