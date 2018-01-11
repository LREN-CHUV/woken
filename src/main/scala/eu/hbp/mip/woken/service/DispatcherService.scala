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

package eu.hbp.mip.woken.service

import akka.NotUsed
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import org.slf4j.LoggerFactory
import eu.hbp.mip.woken.config.{ Dataset, RemoteLocation }
import eu.hbp.mip.woken.fp.Traverse
import eu.hbp.mip.woken.messages.external.{ DatasetId, QueryResult }
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation

import cats.implicits._
import eu.hbp.mip.woken.backends.woken.WokenService

class DispatcherService(datasets: Map[DatasetId, Dataset], wokenService: WokenService) {

  def dispatchTo(dataset: DatasetId): Option[RemoteLocation] =
    if (datasets.isEmpty)
      None
    else
      datasets.get(dataset).flatMap(_.location)

  def dispatchTo(datasets: Set[DatasetId]): (Set[RemoteLocation], Boolean) = {
    val urls     = datasets.map(dispatchTo)
    val local    = urls.contains(None)
    val maybeSet = Traverse.sequence(urls.filter(_.nonEmpty))

    (maybeSet.getOrElse(Set.empty), local)
  }

  def remoteDispatchFlow(datasets: Set[DatasetId]): Source[(RemoteLocation, QueryResult), NotUsed] =
    Source(dispatchTo(datasets)._1)
      .buffer(100, OverflowStrategy.backpressure)
      .via(wokenService.queryFlow)

  def localDispatchFlow(datasets: Set[DatasetId]): Source[QueryResult, NotUsed] = ???

}

object DispatcherService {

  private val logger = LoggerFactory.getLogger("DispatcherService")

  private[service] def loadDatasets(
      datasets: Validation[Map[DatasetId, Dataset]]
  ): Map[DatasetId, Dataset] =
    datasets.getOrElse {
      logger.info("No datasets configured")
      Map.empty
    }

  def apply(datasets: Validation[Map[DatasetId, Dataset]],
            wokenService: WokenService): DispatcherService =
    new DispatcherService(loadDatasets(datasets), wokenService)

}
