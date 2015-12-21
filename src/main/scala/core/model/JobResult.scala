package core.model

import java.time.{LocalDateTime, ZoneOffset, OffsetDateTime}

import spray.json._

case class JobResult(jobId: String, node: String, timestamp: OffsetDateTime,
                      data: Option[String] = None, error: Option[String] = None) {

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

  implicit val jobResultFormat = lazyFormat(jsonFormat5(JobResult.apply))
}

/**
  * Adapt the conversion to JSON to match the horrible JSON format required by the frontend.
  */
object FederationJobResult extends DefaultJsonProtocol {

  implicit val OffsetDateTimeJsonFormat = JobResult.OffsetDateTimeJsonFormat

  implicit object jobResultFormat extends JsonFormat[JobResult] {
    override def write(x: JobResult): JsValue = {
      ???
    }
    override def read(value: JsValue): JobResult = throw new NotImplementedError("Cannot read from the FederationJobResult Json format")
  }

}