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

import java.net.URL

import com.typesafe.config.Config
import eu.hbp.mip.woken.config.{ DatasetLocation, DatasetsConfiguration }
import eu.hbp.mip.woken.fp.Traverse
import eu.hbp.mip.woken.messages.external.DatasetId
import org.slf4j.LoggerFactory

import cats.implicits._

class DispatcherService(config: Config) {
  import DispatcherService._

  val datasets: Map[DatasetId, DatasetLocation] = loadDatasets(config)

  def dispatchTo(dataset: DatasetId): Option[URL] =
    if (datasets.isEmpty)
      None
    else
      datasets.get(dataset).flatMap(_.location).map(new URL(_))

  def dispatchTo(datasets: Set[DatasetId]): (Set[URL], Boolean) = {
    val urls     = datasets.map(dispatchTo)
    val local    = urls.contains(None)
    val maybeSet = Traverse.sequence(urls.filter(_.nonEmpty))

    (maybeSet.getOrElse(Set.empty), local)
  }

}

object DispatcherService {

  private val logger = LoggerFactory.getLogger("DispatcherService")

  private[service] def loadDatasets(config: Config): Map[DatasetId, DatasetLocation] =
    DatasetsConfiguration.datasets(config).getOrElse {
      logger.info("No datasets configured")
      Map.empty
    }

  def apply(config: Config): DispatcherService = new DispatcherService(config)

}
