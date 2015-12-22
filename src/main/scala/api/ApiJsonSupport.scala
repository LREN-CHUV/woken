package api

import core.Error
import core.model.JobResult
import spray.json._

object ApiJsonSupport extends DefaultJsonProtocol {

  implicit val OffsetDateTimeJsonFormat = JobResult.OffsetDateTimeJsonFormat

  implicit val SubGroupJsonFormat = jsonFormat2(Subgroup)
  implicit val GroupJsonFormat = jsonFormat3(Group)
  implicit val VariableIdJsonFormat = jsonFormat1(VariableId)
  implicit val VariableJsonFormat = jsonFormat3(Variable)
  implicit val DatasetJsonFormat = jsonFormat4(Dataset)

  implicit val ErrorJsonFormat = jsonFormat1(Error)

  def jsonEnum[T <: Enumeration](enu: T) = new JsonFormat[T#Value] {
		def write(obj: T#Value) = JsString(obj.toString)

		def read(json: JsValue) = json match {
			case JsString(txt) => enu.withName(txt)
			case something => throw new DeserializationException(s"Expected a value from enum $enu instead of $something")
		}
	}

  implicit val OperatorsJsonFormat = jsonEnum(Operators)
  implicit val FilterJsonFormat = jsonFormat3(Filter)
  implicit val QueryJsonFormat = jsonFormat4(Query)
}

