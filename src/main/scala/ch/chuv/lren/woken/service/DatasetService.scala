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

import ch.chuv.lren.woken.config.DatasetsConfiguration
import ch.chuv.lren.woken.messages.datasets.Dataset
import com.typesafe.config.Config

trait DatasetService {
  def datasets(): Set[Dataset]
}

case class ConfBasedDatasetService(config: Config) extends DatasetService {
  override def datasets(): Set[Dataset] =
    DatasetsConfiguration.datasets(config).getOrElse(Map()).values.toSet
}
