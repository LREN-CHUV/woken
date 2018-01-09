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

import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.client.{ ClusterClient, ClusterClientSettings }
import akka.pattern.ask
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes._
import eu.hbp.mip.woken.api.swagger.MiningServiceApi
import eu.hbp.mip.woken.authentication.BasicAuthentication
import eu.hbp.mip.woken.config.{ AppConfiguration, JobsConfiguration }
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.service.AlgorithmLibraryService
import akka.util.Timeout
import eu.hbp.mip.woken.json.DefaultJsonFormats

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object MiningService

// this trait defines our service behavior independently from the service actor
class MiningService(
    val featuresDatabase: FeaturesDAL,
    override val appConfiguration: AppConfiguration,
    val jobsConf: JobsConfiguration
)(implicit system: ActorSystem)
    extends MiningServiceApi
    with FailureHandling
    with DefaultJsonFormats
    with BasicAuthentication {

  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout                   = Timeout(180.seconds)

  val routes: Route = mining ~ experiment ~ listMethods

  val cluster: ActorRef =
    system.actorOf(ClusterClient.props(ClusterClientSettings(system)), "client")
  val entryPoint = "/user/entrypoint"

  import spray.json._
  import ExternalAPIProtocol._

  override def listMethods: Route = path("mining" / "methods") {
    authenticateBasicAsync(realm = "Woken Secure API", basicAuthenticator) { _ =>
      get {
        complete(AlgorithmLibraryService().algorithms())
      }
    }
  }

  override def mining: Route = path("mining" / "job") {
    authenticateBasicAsync(realm = "Woken Secure API", basicAuthenticator) { user =>
      post {
        entity(as[MiningQuery]) {
          case MiningQuery(userId, variables, covariables, groups, filters, datasets, AlgorithmSpec(c, p))
              if c == "" || c == "data" =>
            ctx =>
              {
                ctx.complete(
                  featuresDatabase.queryData(jobsConf.featuresTable, {
                    variables ++ covariables ++ groups
                  }.distinct.map(_.code))
                )
              }

          case query: MiningQuery =>
            ctx =>
              ctx.complete {
                (cluster ? ClusterClient.Send(entryPoint, query, localAffinity = true))
                  .mapTo[QueryResult]
                  .map {
                    case qr if qr.error.nonEmpty => BadRequest -> qr.toJson
                    case qr if qr.data.nonEmpty  => OK         -> qr.toJson
                  }
              }
        }
      }
    }
  }

  override def experiment: Route = path("mining" / "experiment") {
    authenticateBasicAsync(realm = "Woken Secure API", basicAuthenticator) { user =>
      post {
        entity(as[ExperimentQuery]) { query: ExperimentQuery =>
          complete {
            (cluster ? ClusterClient.Send(entryPoint, query, localAffinity = true))
              .mapTo[QueryResult]
              .map {
                case qr if qr.error.nonEmpty => BadRequest -> qr.toJson
                case qr if qr.data.nonEmpty  => OK         -> qr.toJson
              }
          }
        }
      }
    }
  }

}
