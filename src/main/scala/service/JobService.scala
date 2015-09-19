package service

import akka.io.IO
import akka.util.Timeout
import models.{Process => CWLProcess, ChronosJob}
import router.JobDto
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

trait JobService {

  def add(job: JobDto): Future[Option[CWLProcess]]

}

object JobService extends JobService {

  import _root_.Boot.system

  // TODO: move to a parameter
  val chronosServerUrl = utils.Config.jobs.chronosServerUrl
  override def add(job: JobDto): Future[Option[CWLProcess]] = {
    implicit val timeout: Timeout = Timeout(15.seconds)
    import system.dispatcher // implicit execution context

    val chronosJob = JobToChronos.enrich(job)

    val chronosResponse: Future[HttpResponse] =
      (IO(Http) ? Post(chronosServerUrl + "/scheduler/iso8601", chronosJob)).mapTo[HttpResponse]

    chronosResponse.andThen(???)
    println("Add job")
    Future(Some(CWLProcess(
      id = Some("123"),
      label = job.dockerImage,
      description = Some("Chronos job"),
      inputs = List(),
      outputs = List())))
    /*db.run { for {
      passwordId <- passwordDao.add(UserPassword newWithPassword user.password)
      userId <- userDao.add(populateUser(user).copy(passwordId = Some(passwordId)))
      // "This DBMS allows only a single AutoInc"
      // H2 doesn't allow return the whole user once created so we have to do this instead of returning the object from
      // the dao on inserting
      user <- UserDao.get(userId)
    } yield user */
  }

}
