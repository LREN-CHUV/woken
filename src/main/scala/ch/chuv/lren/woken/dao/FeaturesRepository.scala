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

package ch.chuv.lren.woken.dao

import cats.Applicative
import cats.effect.Resource
import cats.implicits._
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.database.TableId
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.datasets.DatasetId
import spray.json.JsObject
import ch.chuv.lren.woken.messages.query.filters.FilterRule
import doobie.util.log.LogHandler
import doobie.util.update.Update0

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
  * The interface to Features database
  *
  * @tparam F The monad wrapping the connection to the database
  */
trait FeaturesRepository[F[_]] extends Repository {

  /**
    * @return the name of the database as defined in the configuration
    */
  def database: String

  /**
    * @return the list of features tables available in the database
    */
  def tables: Set[FeaturesTableDescription]

  /**
    * Provides the interface to a features table
    *
    * @param table Name of the table
    * @return an option to the features table repository
    */
  def featuresTable(table: TableId): F[Option[FeaturesTableRepository[F]]]

}

trait PrefillExtendedFeaturesTable {

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def prefillExtendedTableSql(targetTable: FeaturesTableDescription, rndColumn: TableColumn)(
      implicit h: LogHandler = LogHandler.nop
  ): Update0
}

/**
  * Provides access to a features table.
  *
  * @tparam F The monad wrapping the connection to the database
  */
trait FeaturesTableRepository[F[_]] extends Repository {

  import FeaturesTableRepository.Headers

  /**
    * Description of the table
    *
    * @return the description
    */
  def table: FeaturesTableDescription

  /**
    * Total number of rows in the table
    *
    * @return number of rows
    */
  def count: F[Int]

  /**
    * Number of rows belonging to the dataset.
    * @param dataset The dataset used to filter rows
    * @return the number of rows in the dataset, 0 if dataset is not associated with the table
    */
  def count(dataset: DatasetId): F[Int]

  /**
    * Number of rows matching the filters.
    * @param filters The filters used to filter rows
    * @return the number of rows in the dataset matching the filters, or the total number of rows if there are no filters
    */
  def count(filters: Option[FilterRule]): F[Int]

  /**
    * @return all headers of the table
    */
  def columns: Headers

  def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])]

  /**
    *
    * @param filters Filters always applied on the queries
    * @param newFeatures New features to create, can be used in machine learning tasks
    * @param otherColumns Other columns to create, present to support some internal functionalities like cross-validation
    * @param prefills List of generators for update statements used to pre-fill the extended features table with data.
    * @return
    */
  def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable]
  ): Validation[Resource[F, FeaturesTableRepository[F]]]
}

object FeaturesTableRepository {

  type Headers = List[TableColumn]

}

class FeaturesInMemoryRepository[F[_]: Applicative](
    override val database: String,
    override val tables: Set[FeaturesTableDescription]
) extends FeaturesRepository[F] {

  private val cache = new TrieMap[TableId, FeaturesTableRepository[F]]()

  override def featuresTable(table: TableId): F[Option[FeaturesTableRepository[F]]] =
    Option(
      cache
        .getOrElse(table, {
          val ftr = new FeaturesTableInMemoryRepository[F]()
          val _   = cache.put(table, ftr)
          ftr
        })
    ).pure[F]

}

class FeaturesTableInMemoryRepository[F[_]: Applicative] extends FeaturesTableRepository[F] {

  import FeaturesTableRepository.Headers

  override val table =
    FeaturesTableDescription(TableId("in_memory", None, "tmp"),
                             Nil,
                             None,
                             validateSchema = false,
                             None,
                             0.0)

  override def count: F[Int] = 0.pure[F]

  override def count(dataset: DatasetId): F[Int] = 0.pure[F]

  override def count(filters: Option[FilterRule]): F[Int] = 0.pure[F]

  override def columns: Headers = Nil

  override def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])] =
    (List[TableColumn](), List[JsObject]().toStream).pure[F]

  override def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable]
  ): Validation[Resource[F, FeaturesTableRepository[F]]] = "not implemented".invalidNel
}
