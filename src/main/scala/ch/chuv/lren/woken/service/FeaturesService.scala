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

import cats.effect.{ Effect, Resource }
import cats.syntax.validated._
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.dao.{
  FeaturesRepository,
  FeaturesTableRepository,
  PrefillExtendedFeaturesTable
}
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.core.fp.runNow
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.filters.FilterRule
import spray.json.JsObject

import scala.collection.mutable
import scala.language.higherKinds

object FeaturesService {
  def apply[F[_]: Effect](repo: FeaturesRepository[F]): FeaturesService[F] =
    new FeaturesServiceImpl[F](repo)
}

trait FeaturesService[F[_]] {

  def featuresTable(dbSchema: Option[String], table: String): Validation[FeaturesTableService[F]]

}

trait FeaturesTableService[F[_]] {

  def table: FeaturesTableDescription

  def count: F[Int]

  def count(dataset: DatasetId): F[Int]

  def count(filters: Option[FilterRule]): F[Int]

  type Headers = List[TableColumn]

  def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])]

  def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable]
  ): Validation[Resource[F, FeaturesTableService[F]]]

}

class FeaturesServiceImpl[F[_]: Effect](repository: FeaturesRepository[F])
    extends FeaturesService[F] {

  private val featuresTableCache: mutable.Map[String, FeaturesTableService[F]] =
    new mutable.WeakHashMap[String, FeaturesTableService[F]]()

  def featuresTable(dbSchema: Option[String], table: String): Validation[FeaturesTableService[F]] =
    featuresTableCache
      .get(table)
      .orElse {
        runNow(repository.featuresTable(dbSchema, table))
          .map { featuresTable =>
            val service = new FeaturesTableServiceImpl(featuresTable)
            featuresTableCache.put(table, service)
            service
          }
      }
      .fold(
        (s"Table $table cannot be found or has not been configured in the configuration for database '" + repository.database + "'")
          .invalidNel[FeaturesTableService[F]]
      ) { s: FeaturesTableService[F] =>
        s.validNel[String]
      }

}

class FeaturesTableServiceImpl[F[_]: Effect](repository: FeaturesTableRepository[F])
    extends FeaturesTableService[F] {

  override def table: FeaturesTableDescription = repository.table

  def count: F[Int] = repository.count

  def count(dataset: DatasetId): F[Int] = repository.count(dataset)

  def count(filters: Option[FilterRule]): F[Int] = repository.count(filters)

  def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])] = repository.features(query)

  override def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable]
  ): Validation[Resource[F, FeaturesTableService[F]]] =
    repository
      .createExtendedFeaturesTable(filters, newFeatures, otherColumns, prefills)
      .map(
        _.flatMap(
          extendedTable =>
            Resource.make(
              Effect[F].delay(new FeaturesTableServiceImpl(extendedTable): FeaturesTableService[F])
            )(_ => Effect[F].delay(()))
        )
      )

}
