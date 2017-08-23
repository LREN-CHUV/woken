package eu.hbp.mip.woken.core.model

import java.time.{LocalDateTime, ZoneOffset, OffsetDateTime}

import spray.json._

case class JobResult(jobId: String, node: String, timestamp: OffsetDateTime,
                      data: Option[String] = None, error: Option[String] = None,
                      shape: String, function: String) {

}

object JobResult extends DefaultJsonProtocol {

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

  implicit val jobResultFormat: JsonFormat[JobResult] = lazyFormat(jsonFormat7(JobResult.apply))
}
