/*
 * Copyright 2017 LREN CHUV
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

import org.yaml.snakeyaml.{ Yaml => YamlParser }
import spray.json._
import eu.hbp.mip.woken.messages.external.{ Operators, _ }
import eu.hbp.mip.woken.core.Error
import eu.hbp.mip.woken.core.model.JobResult

object ApiJsonSupport extends DefaultJsonProtocol {

  implicit val offsetDateTimeJsonFormat = JobResult.OffsetDateTimeJsonFormat

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
              x =>
                JsObject("code" -> JsString(x._1.toString()), "value" -> JsString(x._2.toString()))
            )
            .toVector
        )
      )

    def read(value: JsValue) = {
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
              x =>
                JsObject("code" -> JsString(x._1.toString()), "value" -> JsString(x._2.toString()))
            )
            .toVector
        )
      )

    def read(value: JsValue) = {
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

  implicit val errorJsonFormat: JsonFormat[Error] = jsonFormat1(Error)

  def jsonEnum[T <: Enumeration](enu: T) = new JsonFormat[T#Value] {
    def write(obj: T#Value) = JsString(obj.toString)

    def read(json: JsValue) = json match {
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

  case class Yaml(yaml: String)

  /**
    * Takes a YAML string and maps it to a Spray JSON AST
    */
  val yaml2Json: (Yaml) => JsValue = load _ andThen asSprayJSON

  private def load(input: Yaml): AnyRef = new YamlParser().load(input.yaml)

  import scala.collection.JavaConverters._

  private def asSprayJSON(obj: Object): JsValue = obj match {
    case x: java.util.Map[Object @unchecked, Object @unchecked] =>
      JsObject(x.asScala.map { case (k, v) => k.toString -> asSprayJSON(v) }.toMap)
    case x: java.util.List[Object @unchecked] =>
      JsArray(x.asScala.map(asSprayJSON).toVector)
    case x: java.util.Set[Object @unchecked] =>
      JsArray(x.asScala.map(asSprayJSON).toVector)
    case i: java.lang.Integer =>
      JsNumber(BigDecimal(i))
    case i: java.lang.Long =>
      JsNumber(BigDecimal(i))
    case i: java.math.BigInteger =>
      JsNumber(BigDecimal(i))
    case i: java.lang.Double =>
      JsNumber(BigDecimal(i))
    case s: java.lang.String =>
      JsString(s)
    case d: java.util.Date =>
      JsString(d.toString)
    case b: java.lang.Boolean =>
      JsBoolean(b)
    case _ => JsNull
  }
}
