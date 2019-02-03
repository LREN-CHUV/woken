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

package ch.chuv.lren.woken.akka

import akka.actor.{ Actor, ActorRef, Props }
import akka.stream.ActorMaterializer
import cats.effect.Effect
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.dispatch.{
  ExperimentQueriesActor,
  MetadataQueriesActor,
  MiningQueriesActor
}
import ch.chuv.lren.woken.messages.{ Ping, Pong }
import ch.chuv.lren.woken.messages.datasets.{ DatasetsQuery, DatasetsResponse }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables._
import ch.chuv.lren.woken.service._
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object MasterRouter {

  def props[F[_]: Effect](config: WokenConfiguration,
                          databaseServices: DatabaseServices[F],
                          backendServices: BackendServices[F]): Props =
    Props(
      new MasterRouter(config, databaseServices, backendServices)
    )

}

class MasterRouter[F[_]: Effect](
    val config: WokenConfiguration,
    val databaseServices: DatabaseServices[F],
    val backendServices: BackendServices[F]
) extends Actor
    with LazyLogging {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  lazy val metadataQueriesWorker: ActorRef   = initMetadataQueriesWorker
  lazy val miningQueriesWorker: ActorRef     = initMiningQueriesWorker
  lazy val experimentQueriesWorker: ActorRef = initExperimentQueriesWorker

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def receive: Receive = {

    // For health checks in the cluster
    case Ping(role) if role.isEmpty || role.contains("woken") =>
      logger.info("Ping received")
      sender() ! Pong(Set("woken"))

    case MethodsQuery =>
      mark("MethodsQueryRequestReceived")
      sender ! MethodsResponse(databaseServices.algorithmLibraryService.algorithms)

    case ds: DatasetsQuery =>
      mark("DatasetsQueryRequestReceived")
      val allDatasets = databaseServices.datasetService.datasets()
      val table       = ds.table.getOrElse(config.jobs.featuresTable)
      val datasets =
        if (table == "*") allDatasets
        else allDatasets.filter(_.tables.contains(table))

      sender ! DatasetsResponse(datasets.map(_.withoutAuthenticationDetails))

    case query: VariablesForDatasetsQuery =>
      metadataQueriesWorker forward MetadataQueriesActor.VariablesForDatasets(query, sender())

    //case MiningQuery(variables, covariables, groups, _, AlgorithmSpec(c, p))
    //    if c == "" || c == "data" =>
    //case query: MiningQuery if query.algorithm.code == "" || query.algorithm.code == "data" =>
    //  featuresDatabase.queryData(jobsConf.featuresTable, query.dbAllVars)
    // TODO To be implemented

    case query: MiningQuery =>
      mark("MiningQueryRequestReceived")
      miningQueriesWorker forward MiningQueriesActor.Mine(query, sender())

    case query: ExperimentQuery =>
      mark("ExperimentQueryRequestReceived")
      experimentQueriesWorker forward ExperimentQueriesActor.Experiment(query, sender())

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private[akka] def initMetadataQueriesWorker: ActorRef =
    context.actorOf(
      MetadataQueriesActor.roundRobinPoolProps(config.config,
                                               backendServices.dispatcherService,
                                               databaseServices.datasetService,
                                               databaseServices.variablesMetaService),
      name = "metadataQueries"
    )

  private[akka] def initMiningQueriesWorker: ActorRef =
    context.actorOf(
      MiningQueriesActor.roundRobinPoolProps(
        config,
        databaseServices,
        backendServices
      ),
      name = "miningQueries"
    )

  private[akka] def initExperimentQueriesWorker: ActorRef =
    context.actorOf(
      ExperimentQueriesActor.roundRobinPoolProps(
        config,
        databaseServices,
        backendServices
      ),
      name = "experimentQueries"
    )

  private[akka] def mark(spanKey: String): Unit = {
    val _ = Kamon.currentSpan().mark(spanKey)
  }
}
