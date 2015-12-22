package api

import core.Error
import core.model.JobResult
import spray.json._

object ApiJsonJsonSupport extends DefaultJsonProtocol {

  implicit val OffsetDateTimeJsonFormat = JobResult.OffsetDateTimeJsonFormat

  implicit val SubGroupJsonFormat = jsonFormat2(Subgroup)
  implicit val GroupJsonFormat = jsonFormat3(Group)
  implicit val VariableIdJsonFormat = jsonFormat1(VariableId)
  implicit val VariableJsonFormat = jsonFormat3(Variable)
  implicit val DatasetJsonFormat = jsonFormat4(Dataset)

  implicit val ErrorJsonFormat = jsonFormat1(Error)
}

