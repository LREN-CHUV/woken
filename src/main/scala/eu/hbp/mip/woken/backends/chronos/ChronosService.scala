/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.backends.chronos

import akka.actor.{ Actor, ActorLogging, Props, Status }
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.{ AskTimeoutException, pipe }
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.backends.HttpClient
import eu.hbp.mip.woken.config.JobsConfiguration
import spray.json.PrettyPrinter

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContextExecutor, Future }
import scala.util.Try

object ChronosService {
  // Requests
  case class Schedule(job: ChronosJob)
  case class Check(jobId: String, job: ChronosJob)
  case class Cleanup(job: ChronosJob)

  // Responses for Schedule
  sealed trait ScheduleResponse
  object Ok                         extends ScheduleResponse
  case class Error(message: String) extends ScheduleResponse

  // Responses for Check
  sealed trait JobLivelinessResponse
  case class JobNotFound(jobId: String)                        extends JobLivelinessResponse
  case class JobComplete(jobId: String, success: Boolean)      extends JobLivelinessResponse
  case class JobQueued(jobId: String)                          extends JobLivelinessResponse
  case class JobUnknownStatus(jobId: String, status: String)   extends JobLivelinessResponse
  case class ChronosUnresponsive(jobId: String, error: String) extends JobLivelinessResponse

  def props(jobsConfig: JobsConfiguration): Props =
    Props(new ChronosService(jobsConfig))

}

//TODO: need to use akka http client
class ChronosService(jobsConfig: JobsConfiguration)
    extends Actor
    with ActorLogging
    with ActorTracing {

  import ChronosService._

  var lastRequest: Long = 0

  def receive: PartialFunction[Any, Unit] = {

    case Schedule(job) =>
      import ChronosJob._
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(30.seconds)
      implicit val actorSystem                                = context.system
      if (System.currentTimeMillis() - lastRequest < 250) {
        Thread.sleep(250)
      }
      log.info(s"Send job to Chronos: ${PrettyPrinter(chronosJobFormat.write(job))}")

      val originalSender             = sender()
      val url                        = jobsConfig.chronosServerUrl + "/v1/scheduler/iso8601"
      val chronosResponse: Future[_] = HttpClient.Post(url, job)

      lastRequest = System.currentTimeMillis()

      chronosResponse
        .map {

          case HttpResponse(statusCode: StatusCode, _, entity, _) =>
            statusCode match {
              case _: StatusCodes.Success => Ok
              case _ =>
                log.warning(
                  s"Post schedule to Chronos on $url returned error $statusCode: ${entity}"
                )
                Error(s"Error $statusCode: ${entity}")
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

    case Check(jobId, job) =>
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(10.seconds)
      implicit val actorSystem                                = context.system
      val pipeline: HttpRequest => Future[HttpResponse]       = HttpClient.sendReceive

      if (System.currentTimeMillis() - lastRequest < 200) {
        Thread.sleep(200)
      }

      val originalSender             = sender()
      val url                        = s"${jobsConfig.chronosServerUrl}/v1/scheduler/jobs/search?name=${job.name}"
      val chronosResponse: Future[_] = pipeline(HttpClient.Get(url))

      lastRequest = System.currentTimeMillis()

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
      implicit val actorSystem                                = context.system
      val pipeline: HttpRequest => Future[HttpResponse]       = HttpClient.sendReceive

      if (System.currentTimeMillis() - lastRequest < 100) {
        Thread.sleep(100)
      }

      val url                        = s"${jobsConfig.chronosServerUrl}/v1/scheduler/job/${job.name}"
      val chronosResponse: Future[_] = pipeline(HttpClient.Delete(url))

      lastRequest = System.currentTimeMillis()

      Try(
        Await.ready(chronosResponse, 1.seconds)
      )
    // We don't care about the response

    case e => log.error(s"Unhandled message: $e")
  }

}
