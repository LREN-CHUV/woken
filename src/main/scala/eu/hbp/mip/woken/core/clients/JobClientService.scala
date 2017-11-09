/*
 * Copyright 2017 LREN CHUV
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

import akka.actor.{ Actor, ActorLogging }
import akka.io.IO
import akka.util.Timeout
import spray.can.Http
import spray.http.{ HttpResponse, StatusCode, StatusCodes }
import spray.httpx.RequestBuilding._

import scala.concurrent.Future
import scala.concurrent.duration._

object JobClientService {

  // Requests
  type Start = eu.hbp.mip.woken.core.CoordinatorActor.Start
  val Start = eu.hbp.mip.woken.core.CoordinatorActor.Start

  // Responses

  case class JobComplete(node: String)
  case class JobError(node: String, message: String)
}

class JobClientService(node: String) extends Actor with ActorLogging {
  import JobClientService._
  import eu.hbp.mip.woken.config.WokenConfig.jobs._

  def receive = {
    case Start(job) => {
      import akka.pattern.{ ask, pipe }
      import spray.httpx.SprayJsonSupport._
      import eu.hbp.mip.woken.api.JobDto._
      implicit val system           = context.system
      implicit val executionContext = context.dispatcher
      implicit val timeout: Timeout = Timeout(180.seconds)

      log.warning(s"Send PUT request to ${nodeConfig(node).jobsUrl}/job")
      log.warning(jobDtoFormat.write(job).prettyPrint)

      val originalSender = sender()
      val jobResponse: Future[HttpResponse] =
        (IO(Http) ? Put(nodeConfig(node).jobsUrl + "/job", job)).mapTo[HttpResponse]

      jobResponse.map {
        case HttpResponse(statusCode: StatusCode, entity, _, _) =>
          statusCode match {
            case ok: StatusCodes.Success => JobComplete(node)
            case _                       => JobError(node, s"Error $statusCode: ${entity.asString}")
          }
      } recoverWith { case e: Throwable => Future(JobError(node, e.toString)) } pipeTo originalSender
    }

  }
}
