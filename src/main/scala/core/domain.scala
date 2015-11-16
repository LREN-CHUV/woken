package core

import akka.actor.ReceiveTimeout
import core.model.JobResult
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json.{JsString, JsValue, RootJsonFormat, DefaultJsonProtocol}

// Messages

trait RestMessage {
  def marshaller: ToResponseMarshaller[this.type]
}

case class PutJobResults(results: scala.collection.Seq[JobResult]) extends RestMessage {
  import PutJobResults._
  import spray.httpx.SprayJsonSupport._
  override def marshaller: ToResponseMarshaller[PutJobResults] = ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(sprayJsonMarshaller(putJobResultsFormat))
}

object PutJobResults extends DefaultJsonProtocol {
  import JobResult._
  implicit val seqJobResultFormat = seqFormat[JobResult]

  implicit object putJobResultsFormat extends RootJsonFormat[PutJobResults] {
    override def write(r: PutJobResults) = seqJobResultFormat.write(r.results)
    override def read(json: JsValue): PutJobResults = throw new NotImplementedError("Cannot read a PutJobResult")
  }
}

// Domain objects

object Ok

case class Error(message: String)

case class Validation(message: String)

// Exceptions

case object ChronosNotReachableException extends Exception("Cannot connect to Chronos")

object DefaultMarshallers extends DefaultJsonProtocol {

  import spray.httpx.SprayJsonSupport._

  implicit val ValidationMarshaller: ToResponseMarshaller[Validation] = ToResponseMarshaller.fromMarshaller(StatusCodes.BadRequest)(jsonFormat1(Validation))
  implicit val ErrorMarshaller: ToResponseMarshaller[Error] = ToResponseMarshaller.fromMarshaller(StatusCodes.BadRequest)(jsonFormat1(Error))

  implicit object ReceiveTimeoutFormat extends RootJsonFormat[ReceiveTimeout] {
    override def write(r: ReceiveTimeout) = JsString("ReceiveTimeoutFormat")
    override def read(json: JsValue): ReceiveTimeout = json match {
      case JsString("ReceiveTimeoutFormat") => ReceiveTimeout
      case _ => throw new IllegalArgumentException("Expected 'ReceiveTimeoutFormat'")
    }
  }
  implicit val ReceiveTimeoutMarshaller: ToResponseMarshaller[ReceiveTimeout] = ToResponseMarshaller.fromMarshaller(StatusCodes.BadRequest)

}