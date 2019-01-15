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

import cats.{ Applicative, Id }
import cats.effect.Resource
import cats.implicits._
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableColumn, TableId }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.messages.datasets.DatasetId
import spray.json._
import spray.json.DefaultJsonProtocol._
import ch.chuv.lren.woken.messages.query.filters._
import doobie.util.log.LogHandler
import doobie.util.update.Update0
import sup.HealthCheck

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
  * The interface to Features database
  *
  * @tparam F The monad wrapping the connection to the database
  */
trait FeaturesRepository[F[_]] extends Repository[F] {

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
  def prefillExtendedTableSql(sourceTable: FeaturesTableDescription,
                              targetTable: FeaturesTableDescription,
                              rndColumn: TableColumn)(
      implicit h: LogHandler = LogHandler.nop
  ): Update0
}

/**
  * Provides access to a features table.
  *
  * @tparam F The monad wrapping the connection to the database
  */
trait FeaturesTableRepository[F[_]] extends Repository[F] {

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
    *
    * @param dataset The dataset used to filter rows
    * @return the number of rows in the dataset, 0 if dataset is not associated with the table
    */
  def count(dataset: DatasetId): F[Int]

  /**
    * Number of rows matching the filters.
    *
    * @param filters The filters used to filter rows
    * @return the number of rows in the dataset matching the filters, or the total number of rows if there are no filters
    */
  def count(filters: Option[FilterRule]): F[Int]

  /**
    * Number of rows grouped by a reference column
    *
    * @return a map containing the number of rows for each value of the group by column
    */
  def countGroupBy(groupByColumn: TableColumn, filters: Option[FilterRule]): F[Map[String, Int]]

  /**
    * @return all headers of the table
    */
  def columns: Headers

  /**
    * Returns the list of datasets effectively used by a query
    *
    * @param filters The filters used to filter rows
    * @return a set of dataset ids
    */
  def datasets(filters: Option[FilterRule]): F[Set[DatasetId]]

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
    override val tables: Set[FeaturesTableDescription],
    val tablesContent: Map[TableId, (Headers, List[JsObject])]
) extends FeaturesRepository[F] {

  private val cache = new TrieMap[TableId, FeaturesTableRepository[F]]()

  override def featuresTable(table: TableId): F[Option[FeaturesTableRepository[F]]] = {
    cache.get(table).orElse {
      tables.find(_.table == table).map { t =>
        val (headers, data) = tablesContent.getOrElse(table, Nil -> Nil)
        cache.getOrElseUpdate(
          table,
          new FeaturesTableInMemoryRepository[F](table, headers, t.datasetColumn, data)
        )
      }
    }
  }.pure[F]

  override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])

}

class FeaturesTableInMemoryRepository[F[_]: Applicative](val tableId: TableId,
                                                         override val columns: List[TableColumn],
                                                         val datasetColumn: Option[TableColumn],
                                                         val dataFeatures: List[JsObject])
    extends FeaturesTableRepository[F] {

  import FeaturesTableRepository.Headers
  import spray.json._

  override val table =
    FeaturesTableDescription(tableId, Nil, datasetColumn, validateSchema = false, None, 0.0)

  override def count: F[Int] = dataFeatures.size.pure[F]

  override def count(dataset: DatasetId): F[Int] =
    datasetColumn.fold(if (dataset.code == tableId.name) count else 0.pure[F])(
      ds =>
        dataFeatures
          .count(
            row => row.fields.getOrElse(ds.name, JsString("")).convertTo[String] == dataset.code
          )
          .pure[F]
    )

  override def count(filters: Option[FilterRule]): F[Int] = filters.fold(count) { f =>
    filter(dataFeatures, f).size.pure[F]
  }

  override def countGroupBy(groupByColumn: TableColumn,
                            filters: Option[FilterRule]): F[Map[String, Int]] =
    filters.fold(countGroupBy(dataFeatures, groupByColumn)) { f =>
      countGroupBy(filter(dataFeatures, f), groupByColumn)
    }

  private def countGroupBy(data: List[JsObject], groupByColumn: TableColumn): F[Map[String, Int]] =
    data
      .groupBy(row => row.fields.getOrElse(groupByColumn.name, JsString("")).convertTo[String])
      .mapValues(_.size)
      .pure[F]

  @SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.TraversableOps"))
  private def filter(data: List[JsObject], filters: FilterRule): List[JsObject] = filters match {
    case SingleFilterRule(_, field, _, _, operator, value) =>
      operator match {
        case Operator.equal     => data.filter(_.fields(field).toString == value.head.toString)
        case Operator.notEqual  => data.filter(_.fields(field).toString != value.head.toString)
        case Operator.isNull    => data.filter(_.fields(field) == JsNull)
        case Operator.isNotNull => data.filter(_.fields(field) != JsNull)
        case _                  => throw new NotImplementedError(s"Filter on operator $operator is not implemented")
      }
    case CompoundFilterRule(condition, rules) =>
      condition match {
        case Condition.and =>
          rules.map(filter(data, _)).foldRight(data)((filtered, curr) => curr.intersect(filtered))
        case Condition.or =>
          rules
            .map(filter(data, _))
            .foldRight(data)((filtered, curr) => curr.union(filtered.diff(curr)))
      }
  }

  override def datasets(filters: Option[FilterRule]): F[Set[DatasetId]] =
    datasetColumn.fold(
      count(filters).map(n => if (n == 0) Set[DatasetId]() else Set(DatasetId(tableId.name)))
    ) { _ =>
      val datasets: Set[DatasetId] = Set()
      datasets.pure[F]
    }

  override def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])] =
    (columns, dataFeatures.toStream).pure[F]

  override def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable]
  ): Validation[Resource[F, FeaturesTableRepository[F]]] = "not implemented".invalidNel

  override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])

}
