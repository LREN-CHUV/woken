package api

import core.Error
import core.model.JobResult
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
  implicit val requestJsonFormat: JsonFormat[Request] = jsonFormat1(Request)
  implicit val queryJsonFormat: RootJsonFormat[Query] = jsonFormat5(Query)
}

