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

package eu.hbp.mip.woken.api

import java.time.OffsetDateTime

import spray.json._
import eu.hbp.mip.woken.messages.external.{ Operators, _ }
import eu.hbp.mip.woken.json.formats

object ApiJsonSupport extends DefaultJsonProtocol {

  implicit val offsetDateTimeJsonFormat: RootJsonFormat[OffsetDateTime] =
    formats.OffsetDateTimeJsonFormat

  implicit val variableIdJsonFormat: JsonFormat[VariableId] = jsonFormat1(VariableId)
  implicit val algorithmJsonFormat: JsonFormat[Algorithm]   = AlgorithmJsonFormat

  implicit object AlgorithmJsonFormat extends JsonFormat[Algorithm] {
    def write(a: Algorithm): JsValue =
      JsObject(
        "code" -> JsString(a.code),
        "name" -> JsString(a.name),
        "parameters" -> JsArray(
          a.parameters
            .map(
              x => JsObject("code" -> JsString(x._1.toString), "value" -> JsString(x._2.toString))
            )
            .toVector
        )
      )

    def read(value: JsValue): Algorithm = {
      val parameters = value.asJsObject
        .fields("parameters")
        .asInstanceOf[JsArray]
        .elements
        .map(
          x =>
            (x.asJsObject.fields("code").convertTo[String],
             x.asJsObject.fields("value").convertTo[String])
        )
        .toMap
      Algorithm(value.asJsObject.fields("code").convertTo[String],
                value.asJsObject.fields("name").convertTo[String],
                parameters)
    }
  }

  implicit val validationJsonFormat: JsonFormat[Validation] = ValidationJsonFormat

  implicit object ValidationJsonFormat extends JsonFormat[Validation] {
    def write(v: Validation): JsValue =
      JsObject(
        "code" -> JsString(v.code),
        "name" -> JsString(v.name),
        "parameters" -> JsArray(
          v.parameters
            .map(
              x => JsObject("code" -> JsString(x._1.toString), "value" -> JsString(x._2.toString))
            )
            .toVector
        )
      )

    def read(value: JsValue): Validation = {
      val parameters = value.asJsObject
        .fields("parameters")
        .asInstanceOf[JsArray]
        .elements
        .map(
          x =>
            (x.asJsObject.fields("code").convertTo[String],
             x.asJsObject.fields("value").convertTo[String])
        )
        .toMap
      Validation(value.asJsObject.fields("code").convertTo[String],
                 value.asJsObject.fields("name").convertTo[String],
                 parameters)
    }
  }

  def jsonEnum[T <: Enumeration](enu: T) = new JsonFormat[T#Value] {
    def write(obj: T#Value) = JsString(obj.toString)

    def read(json: JsValue): enu.Value = json match {
      case JsString(txt) => enu.withName(txt)
      case something =>
        throw DeserializationException(s"Expected a value from enum $enu instead of $something")
    }
  }

  implicit val operatorsJsonFormat                                = jsonEnum(Operators)
  implicit val filterJsonFormat: JsonFormat[Filter]               = jsonFormat3(Filter)
  implicit val SimpleQueryJsonFormat: RootJsonFormat[MiningQuery] = jsonFormat5(MiningQuery)
  implicit val experimentQueryJsonFormat: RootJsonFormat[ExperimentQuery] = jsonFormat6(
    ExperimentQuery
  )

}
