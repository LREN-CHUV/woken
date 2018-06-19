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

object FeaturesService {
  def apply(repo: FeaturesRepository[IO])(implicit E: Effect[IO]): FeaturesService =
    new FeaturesServiceImpl(repo)
}

trait FeaturesService {

  def featuresTable(table: String): Either[String, FeaturesTableService]

}

trait FeaturesTableService {

  def count: IO[Int]

  def count(dataset: DatasetId): IO[Int]

  type Headers = List[ColumnMeta]

  def features(query: FeaturesQuery): IO[(Headers, Stream[JsObject])]

}

class FeaturesServiceImpl(repository: FeaturesRepository[IO])(implicit E: Effect[IO])
    extends FeaturesService {

  private val featuresTableCache: mutable.Map[String, FeaturesTableService] =
    new mutable.WeakHashMap[String, FeaturesTableService]()

  def featuresTable(table: String): Either[String, FeaturesTableService] =
    featuresTableCache
      .get(table.toUpperCase)
      .orElse {
        repository.featuresTable(table.toUpperCase).map { featuresTable =>
          val service = new FeaturesTableServiceImpl(featuresTable)
          featuresTableCache.put(table.toUpperCase, service)
          service
        }
      }
      .toRight(s"Table $table cannot be found or has not been configured")

}

class FeaturesTableServiceImpl(repository: FeaturesTableRepository[IO])(implicit E: Effect[IO])
    extends FeaturesTableService {

  def count: IO[Int] = repository.count

  def count(dataset: DatasetId): IO[Int] = repository.count(dataset)

  def features(query: FeaturesQuery): IO[(Headers, Stream[JsObject])] = repository.features(query)

}
