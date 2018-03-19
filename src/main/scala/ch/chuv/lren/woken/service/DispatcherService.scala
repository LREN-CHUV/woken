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

package ch.chuv.lren.woken.service

import akka.NotUsed
import akka.stream.{ FlowShape, OverflowStrategy }
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Merge, Source }
import ch.chuv.lren.woken.fp.Traverse
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, QueryResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import cats.implicits.catsStdInstancesForOption
import com.typesafe.scalalogging.Logger
import ch.chuv.lren.woken.backends.woken.WokenService
import ch.chuv.lren.woken.messages.datasets.{ Dataset, DatasetId }
import ch.chuv.lren.woken.messages.remoting.RemoteLocation
import ch.chuv.lren.woken.messages.variables.{
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}

class DispatcherService(datasets: Map[DatasetId, Dataset], wokenService: WokenService) {

  def dispatchTo(dataset: DatasetId): Option[RemoteLocation] =
    if (datasets.isEmpty)
      None
    else
      datasets.get(dataset).flatMap(_.location)

  def dispatchTo(datasets: Set[DatasetId]): (Set[RemoteLocation], Boolean) = {
    val maybeLocations = datasets.map(dispatchTo)
    val local          = maybeLocations.isEmpty || maybeLocations.contains(None)
    val maybeSet       = Traverse.sequence(maybeLocations.filter(_.nonEmpty))

    (maybeSet.getOrElse(Set.empty), local)
  }

  def remoteDispatchMiningFlow(): Flow[MiningQuery, (RemoteLocation, QueryResult), NotUsed] =
    Flow[MiningQuery]
      .map(q => dispatchTo(q.datasets)._1.map(ds => ds -> q))
      .mapConcat(identity)
      .buffer(100, OverflowStrategy.backpressure)
      .map { case (l, q) => l.copy(url = l.url.withPath(l.url.path / "mining" / "job")) -> q }
      .via(wokenService.queryFlow)

  def remoteDispatchExperimentFlow()
    : Flow[ExperimentQuery, (RemoteLocation, QueryResult), NotUsed] =
    Flow[ExperimentQuery]
      .map(q => dispatchTo(q.trainingDatasets)._1.map(ds => ds -> q))
      .mapConcat(identity)
      .buffer(100, OverflowStrategy.backpressure)
      .map {
        case (l, q) => l.copy(url = l.url.withPath(l.url.path / "mining" / "experiment")) -> q
      }
      .via(wokenService.queryFlow)

  def dispatchVariablesQueryFlow(
      datasetService: DatasetService,
      variablesMetaService: VariablesMetaService
  ): Flow[VariablesForDatasetsQuery, VariablesForDatasetsResponse, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val broadcast = builder.add(Broadcast[VariablesForDatasetsQuery](2))
      val merger    = builder.add(Merge[VariablesForDatasetsResponse](2))

      broadcast ~> remoteDispatchVariablesQueryFlow() ~> merger
      broadcast ~> localVariablesQueryFlow(datasetService, variablesMetaService) ~> merger

      FlowShape(broadcast.in, merger.out)
    })

  def remoteDispatchVariablesQueryFlow()
    : Flow[VariablesForDatasetsQuery, VariablesForDatasetsResponse, NotUsed] = {
    println("remote dispatching flow...")

    Flow[VariablesForDatasetsQuery]
      .map(q => {
        val target = dispatchTo(q.datasets)
        println(s"target datasets $target")
        target._1.map(location => location -> q)
      })
      .mapConcat(identity)
      .buffer(100, OverflowStrategy.backpressure)
      .map {
        case (l, q) => {
          println(s"remote location: ${l.url}")
          l.copy(url = l.url.withPath(l.url.path / "metadata" / "variables")) -> q
        }
      }
      .via(wokenService.variableMetaFlow)
      .map(_._2)
  }

  def localVariablesQueryFlow(
      datasetService: DatasetService,
      variablesMetaService: VariablesMetaService
  ): Flow[VariablesForDatasetsQuery, VariablesForDatasetsResponse, NotUsed] =
    Flow[VariablesForDatasetsQuery]
      .map(q => {
        datasetService
          .datasets()
          .filter(_.location.isEmpty)
          .filter(ds => q.datasets.isEmpty || q.datasets.contains(ds.dataset))
      })
      .mapConcat(identity)
      .map { ds =>
        ds.dataset -> ds.tables.flatMap(variablesMetaService.get).flatMap(_.select(_ => true))
      }
      .map(varsForDs => varsForDs._2.map(_.copy(datasets = Set(varsForDs._1))))
      .map(vars => VariablesForDatasetsResponse(vars.toSet, None))

  def localDispatchFlow(datasets: Set[DatasetId]): Source[QueryResult, NotUsed] = ???

}

object DispatcherService {

  private val logger = Logger("DispatcherService")

  private[service] def loadDatasets(
      datasets: Validation[Map[DatasetId, Dataset]]
  ): Map[DatasetId, Dataset] =
    datasets.fold({ e =>
      logger.info(s"No datasets configured: $e")
      Map[DatasetId, Dataset]()
    }, identity)

  def apply(datasets: Validation[Map[DatasetId, Dataset]],
            wokenService: WokenService): DispatcherService =
    new DispatcherService(loadDatasets(datasets), wokenService)

}
