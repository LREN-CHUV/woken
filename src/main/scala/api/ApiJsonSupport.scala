package api

import java.time.{ZoneOffset, LocalDateTime, OffsetDateTime}

import spray.json._

object WorkflowJsonSupport extends DefaultJsonProtocol {

  implicit object OffsetDateTimeJsonFormat extends RootJsonFormat[OffsetDateTime] {
    override def write(x: OffsetDateTime) = {
      require(x ne null)
      JsNumber(x.toEpochSecond)
    }
    override def read(value: JsValue) = value match {
      case JsNumber(x) => OffsetDateTime.of(LocalDateTime.ofEpochSecond(x.toLong, 0, ZoneOffset.UTC), ZoneOffset.UTC)
      case unknown => deserializationError("Expected OffsetDateTime as JsNumber, but got " + unknown)
    }
  }

  implicit val workflowValidationResponseProtocol = jsonFormat2(WorkflowValidateResponse)
  implicit val workflowStatusResponseProtocol = jsonFormat2(WorkflowStatusResponse)
  implicit val workflowAbortResponseProtocol = jsonFormat2(WorkflowAbortResponse)
  implicit val workflowSubmitResponseProtocol = jsonFormat2(WorkflowSubmitResponse)
  implicit val workflowOutputResponseProtocol = jsonFormat2(WorkflowOutputResponse)
  implicit val callOutputResponseProtocol = jsonFormat3(CallOutputResponse)
  implicit val callLogsResponseProtocol = jsonFormat3(CallLogs)
  implicit val callStdoutStderrResponse = jsonFormat2(CallStdoutStderrResponse)

  implicit object DateJsonFormat extends RootJsonFormat[DateTime] {
    private val parserISO = ISODateTimeFormat.dateTime()
    override def write(obj: DateTime) = JsString(parserISO.print(obj))

    override def read(json: JsValue): DateTime = json match {
      case JsString(str) => parserISO.parseDateTime(str)
      case unknown => throw new NotImplementedError(s"Cannot parse $unknown to a DateTime")
    }
  }
  implicit val callMetadataProtocol = jsonFormat13(CallMetadata)
  implicit val workflowMetadataResponse = jsonFormat8(WorkflowMetadataResponse)
  implicit val workflowQueryResult = jsonFormat5(WorkflowQueryResult)
  implicit val workflowQueryResponse = jsonFormat1(WorkflowQueryResponse)
}

