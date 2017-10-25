package eu.hbp.mip.woken.api

import org.yaml.snakeyaml.{Yaml => YamlParser}
import spray.json._
import eu.hbp.mip.messages.external.{Operators, _}
import eu.hbp.mip.woken.core.Error
import eu.hbp.mip.woken.core.model.JobResult

object ApiJsonSupport extends DefaultJsonProtocol {

  implicit val offsetDateTimeJsonFormat = JobResult.OffsetDateTimeJsonFormat

  implicit val variableIdJsonFormat: JsonFormat[VariableId] = jsonFormat1(VariableId)
  implicit val algorithmJsonFormat: JsonFormat[Algorithm] = AlgorithmJsonFormat

  implicit object AlgorithmJsonFormat extends JsonFormat[Algorithm] {
    def write(a: Algorithm): JsValue = {
      JsObject("code" -> JsString(a.code), "name" -> JsString(a.name), "parameters" -> JsArray(a.parameters.map(x => JsObject("code" -> JsString(x._1.toString()), "value" -> JsString(x._2.toString()))).toVector))
    }

    def read(value: JsValue) = {
      val parameters = value.asJsObject.fields.get("parameters").get.asInstanceOf[JsArray].elements.map(x => (x.asJsObject.fields.get("code").get.convertTo[String], x.asJsObject.fields.get("value").get.convertTo[String])).toMap
      new Algorithm(value.asJsObject.fields.get("code").get.convertTo[String], value.asJsObject.fields.get("name").get.convertTo[String], parameters)
    }
  }

  implicit val validationJsonFormat: JsonFormat[Validation] = ValidationJsonFormat

  implicit object ValidationJsonFormat extends JsonFormat[Validation] {
    def write(v: Validation): JsValue = {
      JsObject("code" -> JsString(v.code), "name" -> JsString(v.name), "parameters" -> JsArray(v.parameters.map(x => JsObject("code" -> JsString(x._1.toString()), "value" -> JsString(x._2.toString()))).toVector))
    }

    def read(value: JsValue) = {
      val parameters = value.asJsObject.fields.get("parameters").get.asInstanceOf[JsArray].elements.map(x => (x.asJsObject.fields.get("code").get.convertTo[String], x.asJsObject.fields.get("value").get.convertTo[String])).toMap
      new Validation(value.asJsObject.fields.get("code").get.convertTo[String], value.asJsObject.fields.get("name").get.convertTo[String], parameters)
    }
  }



  implicit val errorJsonFormat: JsonFormat[Error] = jsonFormat1(Error)

  def jsonEnum[T <: Enumeration](enu: T) = new JsonFormat[T#Value] {
		def write(obj: T#Value) = JsString(obj.toString)

		def read(json: JsValue) = json match {
			case JsString(txt) => enu.withName(txt)
			case something => throw new DeserializationException(s"Expected a value from enum $enu instead of $something")
		}
	}

  implicit val operatorsJsonFormat = jsonEnum(Operators)
  implicit val filterJsonFormat: JsonFormat[Filter] = jsonFormat3(Filter)
  implicit val SimpleQueryJsonFormat: RootJsonFormat[MiningQuery] = jsonFormat5(MiningQuery)
  implicit val experimentQueryJsonFormat: RootJsonFormat[ExperimentQuery] = jsonFormat6(ExperimentQuery)

  case class Yaml(yaml: String)
  /**
    * Takes a YAML string and maps it to a Spray JSON AST
    */
  val yaml2Json: (Yaml) => JsValue = load _ andThen asSprayJSON

  private def load(input: Yaml): AnyRef = new YamlParser().load(input.yaml)

  import scala.collection.JavaConverters._

  private def asSprayJSON(obj: Object): JsValue = obj match {
    case x: java.util.Map[Object @unchecked, Object @unchecked] =>
      JsObject(x.asScala.map {  case (k, v) => k.toString -> asSprayJSON(v) }.toMap)
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
