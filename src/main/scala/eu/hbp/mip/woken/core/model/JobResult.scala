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

package eu.hbp.mip.woken.core.model

import java.time.{ LocalDateTime, OffsetDateTime, ZoneOffset }

import spray.json._

case class JobResult(jobId: String,
                     node: String,
                     timestamp: OffsetDateTime,
                     shape: String,
                     function: String,
                     data: Option[String] = None,
                     error: Option[String] = None)

object JobResult extends DefaultJsonProtocol {

  implicit object OffsetDateTimeJsonFormat extends RootJsonFormat[OffsetDateTime] {
    override def write(x: OffsetDateTime) = {
      require(x ne null)
      JsNumber(x.toEpochSecond)
    }
    override def read(value: JsValue) = value match {
      case JsNumber(x) =>
        OffsetDateTime.of(LocalDateTime.ofEpochSecond(x.toLong, 0, ZoneOffset.UTC), ZoneOffset.UTC)
      case unknown =>
        deserializationError("Expected OffsetDateTime as JsNumber, but got " + unknown)
    }
  }

  implicit val jobResultFormat: JsonFormat[JobResult] = lazyFormat(jsonFormat7(JobResult.apply))
}
