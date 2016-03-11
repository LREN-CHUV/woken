package api

import core.Error
import core.model.JobResult
import org.yaml.snakeyaml.{Yaml => YamlParser}
import spray.json._

object ApiJsonSupport extends DefaultJsonProtocol {

  implicit val offsetDateTimeJsonFormat = JobResult.OffsetDateTimeJsonFormat

  implicit val subGroupJsonFormat: JsonFormat[Subgroup] = jsonFormat2(Subgroup)
  implicit val groupJsonFormat: JsonFormat[Group] = jsonFormat3(Group)
  implicit val variableIdJsonFormat: JsonFormat[VariableId] = jsonFormat1(VariableId)
  implicit val variableJsonFormat: JsonFormat[Variable] = jsonFormat3(Variable)
  implicit val datasetJsonFormat: JsonFormat[Dataset] = jsonFormat4(Dataset)

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
  implicit val queryJsonFormat: RootJsonFormat[Query] = jsonFormat5(Query)

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

