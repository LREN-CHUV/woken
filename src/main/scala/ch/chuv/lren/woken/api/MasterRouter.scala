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

package ch.chuv.lren.woken.api

import akka.actor.{ Actor, ActorRef, Props }
import akka.stream.ActorMaterializer
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.core._
import ch.chuv.lren.woken.messages.datasets.{ DatasetsQuery, DatasetsResponse }
import ch.chuv.lren.woken.service.{ DatasetService, DispatcherService }

import scala.concurrent.ExecutionContext
import ch.chuv.lren.woken.service.{ AlgorithmLibraryService, VariablesMetaService }
import MiningQueries._
import ch.chuv.lren.woken.dispatch.{
  ExperimentQueriesActor,
  MetadataQueriesActor,
  MiningQueriesActor
}
import ch.chuv.lren.woken.config.{ AlgorithmDefinition, AppConfiguration }
import ch.chuv.lren.woken.core.model.Job
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.variables._
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon

object MasterRouter {

  def props(config: Config,
            appConfiguration: AppConfiguration,
            coordinatorConfig: CoordinatorConfig,
            datasetService: DatasetService,
            variablesMetaService: VariablesMetaService,
            dispatcherService: DispatcherService,
            algorithmLibraryService: AlgorithmLibraryService,
            algorithmLookup: String => Validation[AlgorithmDefinition]): Props =
    Props(
      new MasterRouter(
        config,
        appConfiguration,
        coordinatorConfig,
        dispatcherService,
        algorithmLibraryService,
        datasetService,
        variablesMetaService,
        experimentQuery2Job(variablesMetaService, coordinatorConfig.jobsConf, algorithmLookup),
        miningQuery2Job(variablesMetaService, coordinatorConfig.jobsConf, algorithmLookup)
      )
    )

}

case class MasterRouter(config: Config,
                        appConfiguration: AppConfiguration,
                        coordinatorConfig: CoordinatorConfig,
                        dispatcherService: DispatcherService,
                        algorithmLibraryService: AlgorithmLibraryService,
                        datasetService: DatasetService,
                        variablesMetaService: VariablesMetaService,
                        experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job],
                        miningQuery2JobF: MiningQuery => Validation[Job])
    extends Actor
    with LazyLogging {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  lazy val metadataQueriesWorker: ActorRef   = initMetadataQueriesWorker
  lazy val miningQueriesWorker: ActorRef     = initMiningQueriesWorker
  lazy val experimentQueriesWorker: ActorRef = initExperimentQueriesWorker

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def receive: Receive = {

    case MethodsQuery =>
      Kamon.currentSpan().mark("MethodsQueryRequestReceived")
      sender ! MethodsResponse(algorithmLibraryService.algorithms)

    case ds: DatasetsQuery =>
      Kamon.currentSpan().mark("DatasetsQueryRequestReceived")
      val allDatasets = datasetService.datasets()
      val table       = ds.table.getOrElse(coordinatorConfig.jobsConf.featuresTable)
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
      Kamon.currentSpan().mark("MiningQueryRequestReceived")
      miningQueriesWorker forward MiningQueriesActor.Mine(query, sender())

    case query: ExperimentQuery =>
      Kamon.currentSpan().mark("ExperimentQueryRequestReceived")
      experimentQueriesWorker forward ExperimentQueriesActor.Experiment(query, sender())

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private[api] def initMetadataQueriesWorker: ActorRef =
    context.actorOf(
      MetadataQueriesActor.roundRobinPoolProps(config,
                                               dispatcherService,
                                               datasetService,
                                               variablesMetaService),
      name = "metadataQueries"
    )

  private[api] def initMiningQueriesWorker: ActorRef =
    context.actorOf(
      MiningQueriesActor.roundRobinPoolProps(config,
                                             coordinatorConfig,
                                             dispatcherService,
                                             variablesMetaService,
                                             miningQuery2JobF),
      name = "miningQueries"
    )

  private[api] def initExperimentQueriesWorker: ActorRef =
    context.actorOf(
      ExperimentQueriesActor.roundRobinPoolProps(config,
                                                 coordinatorConfig,
                                                 dispatcherService,
                                                 experimentQuery2JobF),
      name = "experimentQueries"
    )

}
