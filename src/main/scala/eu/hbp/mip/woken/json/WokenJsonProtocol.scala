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
import spray.json.{ DefaultJsonProtocol, JsObject, JsString, JsValue, JsonFormat, RootJsonFormat }

object WokenJsonProtocol extends DefaultJsonProtocol {

  implicit val offsetDateTimeJsonFormat: RootJsonFormat[OffsetDateTime] =
    formats.OffsetDateTimeJsonFormat

  implicit val variableId = jsonFormat1(VariableId)

  implicit object CodeValueFormat extends JsonFormat[CodeValue] {
    override def write(obj: CodeValue): JsValue =
      JsObject(
        "code"  -> JsString(obj.code),
        "value" -> JsString(obj.value)
      )

    override def read(json: JsValue): CodeValue =
      CodeValue(
        json.asJsObject.fields("code").convertTo[String],
        json.asJsObject.fields("value").convertTo[String]
      )
  }

  implicit val validationSpec = jsonFormat2(ValidationSpec)

  implicit val algorithmSpec = jsonFormat2(AlgorithmSpec)

  implicit val experimentQuery = jsonFormat6(ExperimentQuery)

  implicit val miningQuery = jsonFormat5(MiningQuery)

  implicit val queryResult = jsonFormat7(QueryResult)
}
