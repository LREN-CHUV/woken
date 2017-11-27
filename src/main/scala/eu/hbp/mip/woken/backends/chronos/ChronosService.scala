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

import akka.actor.{ Actor, ActorLogging, ActorSystem, Status }
import akka.io.IO
import akka.pattern.{ AskTimeoutException, ask, pipe }
import akka.util.Timeout
import com.github.levkhomich.akka.tracing.ActorTracing
import spray.can.Http
import spray.http.{ HttpResponse, StatusCode, StatusCodes }
import spray.httpx.RequestBuilding._
import spray.json.PrettyPrinter

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContextExecutor, Future }

object ChronosService {
  // Requests
  case class Schedule(job: ChronosJob)
  case class Check(job: ChronosJob)

  // Responses
  val Ok = eu.hbp.mip.woken.core.Ok
  type Error = eu.hbp.mip.woken.core.Error
  val Error = eu.hbp.mip.woken.core.Error
}

class ChronosService extends Actor with ActorLogging with ActorTracing {
  import ChronosService._
  import eu.hbp.mip.woken.config.WokenConfig.jobs._

  def receive: PartialFunction[Any, Unit] = {

    case Schedule(job) =>
      import ChronosJob._
      import spray.httpx.SprayJsonSupport._

      implicit val system: ActorSystem                        = context.system
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(30.seconds)

      log.info(s"Send job to Chronos: ${PrettyPrinter(chronosJobFormat.write(job))}")

      val originalSender = sender()
      val postUrl        = chronosServerUrl + "/v1/scheduler/iso8601"
      val chronosResponse: Future[_] =
        IO(Http) ? Post(postUrl, job)

      chronosResponse
        .map {

          case HttpResponse(statusCode: StatusCode, entity, _, _) =>
            statusCode match {
              case _: StatusCodes.Success => Ok
              case _ =>
                log.warning(
                  s"Post schedule to Chronos on $postUrl returned error $statusCode: ${entity.asString}"
                )
                Error(s"Error $statusCode: ${entity.asString}")
            }

          case f: Status.Failure =>
            log.warning(
              s"Post schedule to Chronos on $postUrl returned error ${f.cause.getMessage}"
            )
            Error(f.cause.getMessage)
        }
        .recover {

          case _: AskTimeoutException =>
            log.warning(s"Post schedule to Chronos on $postUrl timed out after $timeout")
            Error("Connection timeout")

          case e: Throwable =>
            log.warning(s"Post schedule to Chronos on $postUrl returned an error $e")
            Error(e.getMessage)

        } pipeTo originalSender

    case Check(job) =>
      implicit val system: ActorSystem                        = context.system
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(30.seconds)

      val originalSender = sender()
      val postUrl        = s"$chronosServerUrl/v1/scheduler/jobs/search?name=${job.name}"
      val chronosResponse: Future[_] =
        IO(Http) ? Post(postUrl)

      chronosResponse
        .map {

          case HttpResponse(statusCode: StatusCode, entity, _, _) =>
            statusCode match {
              case _: StatusCodes.Success =>
                import spray.json._
                val json = entity.asString.parseJson
                // TODO: parse json, find if job executed, on error...
                Ok

              case _ =>
                log.warning(
                  s"Post search to Chronos on $postUrl returned error $statusCode: ${entity.asString}"
                )
                Error(s"Error $statusCode: ${entity.asString}")
            }

          case f: Status.Failure =>
            log.warning(
              s"Post search to Chronos on $postUrl returned error ${f.cause.getMessage}"
            )
            Error(f.cause.getMessage)
        }
        .recover {

          case _: AskTimeoutException =>
            log.warning(s"Post search to Chronos on $postUrl timed out after $timeout")
            Error("Connection timeout")

          case e: Throwable =>
            log.warning(s"Post search to Chronos on $postUrl returned an error $e")
            Error(e.getMessage)

        } pipeTo originalSender

    case e => log.error(s"Unhandled message: $e")
  }
}
