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

package eu.hbp.mip.woken.json

import java.time.OffsetDateTime

import eu.hbp.mip.woken.messages.external._
import spray.json._

// TODO: remove or merge with ExternalApiProtocol
object ApiJsonSupport extends DefaultJsonProtocol {

  import ExternalAPIProtocol._

  // TODO: will be in woken-messages 2.1.6
  implicit val offsetDateTimeJsonFormat: RootJsonFormat[OffsetDateTime] =
    formats.OffsetDateTimeJsonFormat

  implicit val FilterJsonFormat: JsonFormat[Filter]               = jsonFormat3(Filter)
  implicit val SimpleQueryJsonFormat: RootJsonFormat[MiningQuery] = jsonFormat5(MiningQuery)
  implicit val ExperimentQueryJsonFormat: RootJsonFormat[ExperimentQuery] = jsonFormat6(
    ExperimentQuery
  )

  implicit val QueryResultJsonFormat: JsonFormat[QueryResult] = jsonFormat7(QueryResult)
}
