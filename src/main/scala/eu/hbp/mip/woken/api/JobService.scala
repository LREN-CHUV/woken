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

package eu.hbp.mip.woken.api

import akka.actor.{ ActorRef, ActorSystem, Props }
import spray.http._
import spray.routing.Route
import spray.json._
import MediaTypes._

import eu.hbp.mip.woken.core.{ CoordinatorActor, ExperimentActor, JobResults, RestMessage }
import eu.hbp.mip.woken.dao.{ JobResultsDAL, LdsmDAL }

// this trait defines our service behavior independently from the service actor
class JobService(val chronosService: ActorRef,
                 val resultDatabase: JobResultsDAL,
                 val federationDatabase: Option[JobResultsDAL],
                 val ldsmDatabase: LdsmDAL)(implicit system: ActorSystem)
    extends JobServiceDoc
    with PerRequestCreator
    with DefaultJsonFormats {

  override def context = system
  val routes: Route    = initJob

  import JobDto._
  import CoordinatorActor._
  import ApiJsonSupport._

  implicit object EitherErrorSelector extends ErrorSelector[ErrorResponse.type] {
    def apply(v: ErrorResponse.type): StatusCode = StatusCodes.BadRequest
  }

  override def initJob: Route = path("job") {
    post {
      entity(as[JobDto]) { job =>
        chronosJob() {
          Start(job)
        }
      }
    }
  }

  def chronosJob(
      jobResultsFactory: JobResults.Factory = JobResults.defaultFactory
  )(message: RestMessage): Route =
    ctx =>
      perRequest(ctx,
                 CoordinatorActor.props(chronosService,
                                        resultDatabase,
                                        federationDatabase,
                                        jobResultsFactory),
                 message)
}
