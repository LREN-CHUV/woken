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

import ch.chuv.lren.woken.messages.datasets.{ AnonymisationLevel, Dataset, DatasetId }
import com.typesafe.config.Config

import scala.collection.JavaConversions.iterableAsScalaIterable

trait DatasetService {
  def datasets(): Set[Dataset]
}

case class ConfBasedDatasetService(config: Config) extends DatasetService {
  private val datasetConfig = config.getConfig("datasets")

  override def datasets(): Set[Dataset] =
    datasetConfig
      .entrySet()
      .map(datasetEntry => {
        val confValue = datasetEntry.getValue.atKey(".")
        Dataset(
          DatasetId(datasetEntry.getKey),
          confValue.getString("label"),
          confValue.getString("description"),
          confValue.getStringList("tables").toList,
          AnonymisationLevel.withName(confValue.getString("anonymisationLevel")),
          None
        )
      })
      .toSet

}
