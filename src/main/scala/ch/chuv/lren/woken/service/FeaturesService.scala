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

import cats.effect.{ Effect, IO }
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.dao.{ ColumnMeta, FeaturesRepository, FeaturesTableRepository }
import ch.chuv.lren.woken.messages.datasets.DatasetId
import spray.json.JsObject

import scala.collection.mutable

class FeaturesService(repository: FeaturesRepository[IO])(implicit E: Effect[IO]) {

  private val featuresTableCache: mutable.Map[String, FeaturesTableService] =
    new mutable.WeakHashMap[String, FeaturesTableService]()

  def featuresTable(table: String): FeaturesTableService = {
    val ft = featuresTableCache.get(table.toUpperCase)
    ft.fold {
      val featuresTable = repository.featuresTable(table.toUpperCase)
      val service       = new FeaturesTableService(featuresTable)
      featuresTableCache.put(table.toUpperCase, service)
      service
    }(identity)
  }

}

class FeaturesTableService(repository: FeaturesTableRepository[IO])(implicit E: Effect[IO]) {

  def countIO: IO[Int] = repository.count
  def count: Int       = countIO.unsafeRunSync()

  def countIO(dataset: DatasetId): IO[Int] = repository.count(dataset)
  def count(dataset: DatasetId): Int       = countIO(dataset).unsafeRunSync()

  type Headers = List[ColumnMeta]

  def featuresIO(query: FeaturesQuery): IO[(Headers, Stream[JsObject])] = repository.features(query)
  def features(query: FeaturesQuery): (Headers, Stream[JsObject]) =
    featuresIO(query).unsafeRunSync()

}

object FeaturesService {
  def apply(repo: FeaturesRepository[IO])(implicit E: Effect[IO]): FeaturesService =
    new FeaturesService(repo)
}
