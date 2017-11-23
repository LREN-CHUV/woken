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

package eu.hbp.mip.woken.core.clients

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.concurrent.duration._
import akka.actor.{ Actor, ActorLogging, ActorSystem, Status }
import akka.io.IO
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import com.github.levkhomich.akka.tracing.ActorTracing
import spray.can.Http
import spray.http.{ HttpResponse, StatusCode, StatusCodes }
import spray.httpx.RequestBuilding._
import eu.hbp.mip.woken.core.model.ChronosJob
import spray.json.PrettyPrinter

object ChronosService {
  // Requests
  case class Schedule(job: ChronosJob)

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
      import akka.pattern.{ ask, pipe }
      import spray.httpx.SprayJsonSupport._
      import ChronosJob._

      implicit val system: ActorSystem                        = context.system
      implicit val executionContext: ExecutionContextExecutor = context.dispatcher
      implicit val timeout: Timeout                           = Timeout(30.seconds)

      log.info(s"Send job to Chronos: ${PrettyPrinter(chronosJobFormat.write(job))}")

      val originalSender = sender()
      val chronosResponse: Future[_] =
        IO(Http) ? Post(chronosServerUrl + "/scheduler/iso8601", job)

      chronosResponse
        .map {

          case HttpResponse(statusCode: StatusCode, entity, _, _) =>
            statusCode match {
              case _: StatusCodes.Success => Ok
              case _ =>
                log.warning(
                  s"Post to Chronos on $chronosServerUrl returned error $statusCode: ${entity.asString}"
                )
                Error(s"Error $statusCode: ${entity.asString}")
            }

          case f: Status.Failure =>
            log.warning(
              s"Post to Chronos on $chronosServerUrl returned error ${f.cause.getMessage}"
            )
            Error(f.cause.getMessage)
        }
        .recover {

          case _: AskTimeoutException =>
            log.warning(s"Post to Chronos on $chronosServerUrl timed out after $timeout")
            Error("Connection timeout")

          case e: Throwable =>
            log.warning(s"Post to Chronos on $chronosServerUrl returned an error $e")
            Error(e.getMessage)

        } pipeTo originalSender

    case _ =>
  }
}
