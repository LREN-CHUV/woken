package core.model

import java.time.{LocalDateTime, ZoneOffset, OffsetDateTime}

import spray.json._

case class JobResult(jobId: String, node: String, timestamp: OffsetDateTime,
                      data: Option[String] = None, error: Option[String] = None) {

}

object JobResult extends DefaultJsonProtocol {

  implicit object OffsetDateTimeJsonFormat extends JsonFormat[OffsetDateTime] {
    def write(x: OffsetDateTime) = {
      require(x ne null)
      JsNumber(x.toEpochSecond)
    }
    def read(value: JsValue) = value match {
      case JsNumber(x) => OffsetDateTime.of(LocalDateTime.ofEpochSecond(x.toLong, 0, ZoneOffset.UTC), ZoneOffset.UTC)
      case x => deserializationError("Expected OffsetDateTime as JsNumber, but got " + x)
    }
  }

  implicit val jobResultFormat = lazyFormat(jsonFormat5(JobResult.apply))
}