package service

import akka.io.IO
import akka.util.Timeout
import models.{Process => CWLProcess, ChronosJob}
import router.{ResultDto, JobDto}
import spray.httpx.marshalling.Marshaller
import utils.JobToChronos

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

import spray.can.Http
import spray.http._
import HttpMethods._
import ContentTypes._
import spray.httpx.RequestBuilding._
import spray.httpx.SprayJsonSupport._

trait ChronosJobService {

  def add(job: JobDto): Future[Option[ResultDto]]

}

class ChronosJobServiceImpl(val chronosServerUrl: String) extends ChronosJobService {


  import Boot. _root_.Boot.system
  import akka.pattern.ask

  override def add(job: JobDto): Future[Option[ResultDto]] = {

    implicit val timeout: Timeout = Timeout(15.seconds)
    import system.dispatcher // implicit execution context

    val chronosJob = JobToChronos.enrich(job)

    val chronosResponse: Future[HttpResponse] =
      (IO(Http) ? Post(chronosServerUrl + "/scheduler/iso8601", chronosJob)).mapTo[HttpResponse]

    chronosResponse.map(???)

  }
}
