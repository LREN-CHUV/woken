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

import cats.effect.Effect
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.TableColumn
import ch.chuv.lren.woken.dao.{ FeaturesRepository, FeaturesTableRepository }
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.fp.runNow
import spray.json.JsObject

import scala.collection.mutable
import scala.language.higherKinds

object FeaturesService {
  def apply[F[_]: Effect](repo: FeaturesRepository[F]): FeaturesService[F] =
    new FeaturesServiceImpl[F](repo)
}

trait FeaturesService[F[_]] {

  def featuresTable(table: String): Either[String, FeaturesTableService[F]]

}

trait FeaturesTableService[F[_]] {

  def count: F[Int]

  def count(dataset: DatasetId): F[Int]

  type Headers = List[TableColumn]

  def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])]

}

class FeaturesServiceImpl[F[_]: Effect](repository: FeaturesRepository[F])
    extends FeaturesService[F] {

  private val featuresTableCache: mutable.Map[String, FeaturesTableService[F]] =
    new mutable.WeakHashMap[String, FeaturesTableService[F]]()

  def featuresTable(table: String): Either[String, FeaturesTableService[F]] =
    featuresTableCache
      .get(table)
      .orElse {
        runNow(repository.featuresTable(table))
          .map { featuresTable =>
            val service = new FeaturesTableServiceImpl(repository.database, featuresTable)
            featuresTableCache.put(table, service)
            service
          }
      }
      .toRight(
        s"Table $table cannot be found or has not been configured in the configuration for database '" + repository.database + "'"
      )

}

class FeaturesTableServiceImpl[F[_]: Effect](database: String,
                                             repository: FeaturesTableRepository[F])
    extends FeaturesTableService[F] {

  def count: F[Int] = repository.count

  def count(dataset: DatasetId): F[Int] = repository.count(dataset)

  def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])] = repository.features(query)

}
