/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.backends.chronos

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props, Status }
import akka.http.scaladsl.model._
import akka.pattern.{ AskTimeoutException, pipe }
import akka.util.Timeout
//import com.github.levkhomich.akka.tracing.ActorTracing
import ch.chuv.lren.woken.backends.HttpClient
import ch.chuv.lren.woken.config.JobsConfiguration

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.util.{ Failure, Success }

object ChronosService {

  // Requests
  sealed trait Request

  case class Schedule(job: ChronosJob, originator: ActorRef) extends Request

  case class Check(jobId: String, job: ChronosJob, originator: ActorRef) extends Request

  case class Cleanup(job: ChronosJob) extends Request

  sealed trait Response

  // Responses for Schedule
  sealed trait ScheduleResponse extends Response

  case object Ok extends ScheduleResponse

  case class Error(message: String) extends ScheduleResponse

  // Responses for Check
  sealed trait JobLivelinessResponse extends Response

  case class JobNotFound(jobId: String) extends JobLivelinessResponse

  case class JobComplete(jobId: String, success: Boolean) extends JobLivelinessResponse

  case class JobQueued(jobId: String) extends JobLivelinessResponse

  case class JobUnknownStatus(jobId: String, status: String) extends JobLivelinessResponse

  case class ChronosUnresponsive(jobId: String, error: String) extends JobLivelinessResponse

  def props(jobsConfig: JobsConfiguration): Props =
    Props(new ChronosService(jobsConfig))

}

class ChronosService(jobsConfig: JobsConfiguration)
    extends Actor
    with ActorLogging
    /*with ActorTracing*/ {

  import ChronosService._

  def receive: PartialFunction[Any, Unit] = {

    case Schedule(job, originator) =>
      import ChronosJob._
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(30.seconds)
      implicit val actorSystem: ActorSystem                   = context.system

      log.debug(s"Send job to Chronos: ${chronosJobFormat.write(job).prettyPrint}")

      val originalSender             = originator
      val chronosUrl                 = Uri(jobsConfig.chronosServerUrl)
      val url                        = chronosUrl.withPath(chronosUrl.path + "/v1/scheduler/iso8601")
      val chronosResponse: Future[_] = HttpClient.Post(url, job)

      chronosResponse
        .map {

          case HttpResponse(statusCode: StatusCode, _, entity, _) =>
            statusCode match {
              case _: StatusCodes.Success => {
                log.debug("Chronos says success")
                Ok
              }
              case _ =>
                log.warning(
                  s"Post schedule to Chronos on $url returned error $statusCode: $entity"
                )
                Error(s"Error $statusCode: $entity")
            }

          case f: Status.Failure =>
            log.warning(
              s"Post schedule to Chronos on $url returned error ${f.cause.getMessage}"
            )
            Error(f.cause.getMessage)
        }
        .recover {

          case _: AskTimeoutException =>
            log.warning(s"Post schedule to Chronos on $url timed out after $timeout")
            Error("Connection timeout")

          case e: Throwable =>
            log.warning(s"Post schedule to Chronos on $url returned an error $e")
            Error(e.getMessage)

        } pipeTo originalSender
    // TODO: could use supervisedPipe here: http://pauljamescleary.github.io/futures-in-akka/

    case Check(jobId, job, originator) =>
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(10.seconds)
      implicit val actorSystem: ActorSystem                   = context.system
      val pipeline: HttpRequest => Future[HttpResponse]       = HttpClient.sendReceive

      val originalSender             = originator
      val url                        = s"${jobsConfig.chronosServerUrl}/v1/scheduler/jobs/search?name=${job.name}"
      val chronosResponse: Future[_] = pipeline(HttpClient.Get(url))

      chronosResponse
        .map {

          case HttpResponse(statusCode: StatusCode, _, entity, _) =>
            statusCode match {
              case _: StatusCodes.Success =>
                val response: Future[List[ChronosJobLiveliness]] =
                  HttpClient.unmarshalChronosResponse(entity)

                // TODO: parse json, find if job executed, on error...
                response.map { resp =>
                  val liveliness = resp.headOption

                  val status: JobLivelinessResponse = liveliness match {
                    case None => JobNotFound(jobId)
                    case Some(ChronosJobLiveliness(_, successCount, _, _, _, _, _, true))
                        if successCount > 0 =>
                      JobComplete(jobId, success = true)
                    case Some(
                        ChronosJobLiveliness(_, successCount, errorCount, _, _, softError, _, _)
                        ) if successCount == 0 && (errorCount > 0 || softError) =>
                      JobComplete(jobId, success = false)
                    case Some(ChronosJobLiveliness(_, _, _, _, _, _, _, false)) => JobQueued(jobId)
                    case Some(l: ChronosJobLiveliness)                          => JobUnknownStatus(jobId, l.toString)
                  }
                  status
                }

              case _ =>
                log.warning(
                  s"Post search to Chronos on $url returned error $statusCode: ${entity.toString}"
                )
                ChronosUnresponsive(jobId, s"Error $statusCode: ${entity.toString}")
            }

          case f: Status.Failure =>
            log.warning(
              s"Post search to Chronos on $url returned error ${f.cause.getMessage}"
            )
            ChronosUnresponsive(jobId, f.cause.getMessage)
        }
        .recover {

          case _: AskTimeoutException =>
            log.warning(s"Post search to Chronos on $url timed out after $timeout")
            ChronosUnresponsive(jobId, "Connection timeout")

          case e: Throwable =>
            log.warning(s"Post search to Chronos on $url returned an error $e")
            ChronosUnresponsive(jobId, e.getMessage)

        } pipeTo originalSender

    case Cleanup(job) =>
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(10.seconds)
      implicit val actorSystem: ActorSystem                   = context.system
      val pipeline: HttpRequest => Future[HttpResponse]       = HttpClient.sendReceive

      val url                        = s"${jobsConfig.chronosServerUrl}/v1/scheduler/job/${job.name}"
      val chronosResponse: Future[_] = pipeline(HttpClient.Delete(url))
      chronosResponse.onComplete {
        case Success(_)   =>
        case Failure(err) => log.error("Chronos Cleanup job response error.", err)
      }

    // We don't care about the response

    case e => log.error(s"Unhandled message: $e")
  }

}
