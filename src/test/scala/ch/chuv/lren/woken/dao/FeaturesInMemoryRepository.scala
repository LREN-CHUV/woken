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

import cats.Id
import cats.effect.{ Effect, Resource }
import cats.implicits._
import ch.chuv.lren.woken.config.DatabaseConfiguration
import ch.chuv.lren.woken.core.fp.runNow
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.messages.datasets.{ DatasetId, TableId }
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.validation.{ FeaturesSplitterDefinition, KFoldFeaturesSplitterDefinition }
import sup.HealthCheck
import spray.json._
import spray.json.DefaultJsonProtocol._

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds
import scala.util.{ Failure, Success, Try }

class FeaturesInMemoryRepository[F[_]: Effect](
    override val database: DatabaseConfiguration,
    val tablesContent: Map[TableId, (Headers, List[JsObject])]
) extends FeaturesRepository[F] {

  private val cache = new TrieMap[TableId, FeaturesTableRepository[F]]()

  override def featuresTable(table: TableId): F[Option[FeaturesTableRepository[F]]] = {
    cache.get(table).orElse {
      database.tables.get(table).map { t =>
        val (headers, data) = tablesContent.getOrElse(table, Nil -> Nil)
        cache.getOrElseUpdate(
          table,
          new FeaturesTableInMemoryRepository[F](t, headers, t.datasetColumn, data)
        )
      }
    }
  }.pure[F]

  override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])

}

class FeaturesTableInMemoryRepository[F[_]: Effect](override val table: FeaturesTableDescription,
                                                    override val columns: List[TableColumn],
                                                    val datasetColumn: Option[TableColumn],
                                                    val dataFeatures: List[JsObject])
    extends FeaturesTableRepository[F] {

  import FeaturesTableRepository.Headers
  import spray.json._

  override def count: F[Int] = dataFeatures.size.pure[F]

  override def count(dataset: DatasetId): F[Int] =
    datasetColumn.fold(if (dataset.code == table.table.name) count else 0.pure[F])(
      ds =>
        dataFeatures
          .count(
            row => row.fields.getOrElse(ds.name, JsString("")).convertTo[String] == dataset.code
          )
          .pure[F]
    )

  override def count(filters: Option[FilterRule]): F[Int] = filters.fold(count) { f =>
    filter(dataFeatures, f) match {
      case Success(value)     => value.size.pure[F]
      case Failure(exception) => exception.raiseError[F, Int]
    }
  }

  override def countGroupBy(groupByColumn: TableColumn,
                            filters: Option[FilterRule]): F[Map[String, Int]] =
    filters.fold(countGroupBy(dataFeatures, groupByColumn)) { f =>
      filter(dataFeatures, f) match {
        case Success(value)     => countGroupBy(value, groupByColumn)
        case Failure(exception) => exception.raiseError[F, Map[String, Int]]
      }
    }

  private def countGroupBy(data: List[JsObject], groupByColumn: TableColumn): F[Map[String, Int]] =
    data
      .groupBy(row => row.fields.getOrElse(groupByColumn.name, JsString("")).convertTo[String])
      .mapValues(_.size)
      .pure[F]

  @SuppressWarnings(Array("org.wartremover.warts.Throw", "org.wartremover.warts.TraversableOps"))
  private def filter(data: List[JsObject], filters: FilterRule): Try[List[JsObject]] = Try {
    filters match {
      case SingleFilterRule(_, field, _, _, operator, value) =>
        operator match {
          case Operator.equal     => data.filter(_.fields(field).toString == value.head.toString)
          case Operator.notEqual  => data.filter(_.fields(field).toString != value.head.toString)
          case Operator.isNull    => data.filter(_.fields(field) == JsNull)
          case Operator.isNotNull => data.filter(_.fields(field) != JsNull)
          case Operator.in =>
            data.filter { row =>
              row.fields(field) match {
                case JsString(v) => value.contains(v)
                case _           => false
              }
            }
          case _ =>
            throw new NotImplementedError(s"Filter on operator $operator is not implemented")
        }
      case CompoundFilterRule(condition, rules) =>
        condition match {
          case Condition.and =>
            rules
              .map(filter(data, _))
              .foldRight(data)((filtered, curr) => curr.intersect(filtered.getOrElse(List.empty)))
          case Condition.or =>
            rules
              .map(filter(data, _))
              .foldRight(data)(
                (filtered, curr) => curr.union(filtered.getOrElse(List.empty).diff(curr))
              )
        }
    }
  }

  override def datasets(filters: Option[FilterRule]): F[Set[DatasetId]] =
    datasetColumn.fold(
      count(filters).map(n => if (n == 0) Set[DatasetId]() else Set(DatasetId(table.table.name)))
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
      prefills: List[PrefillExtendedFeaturesTable],
      nextExtendedTableNumber: Int
  ): Validation[Resource[F, FeaturesTableRepository[F]]] =
    ExtendedFeaturesTableInMemoryRepository
      .apply(
        this,
        filters,
        newFeatures,
        otherColumns,
        prefills,
        nextExtendedTableNumber
      )
      .map(r => r.map[FeaturesTableRepository[F]](t => t))

  override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])

}

object ExtendedFeaturesTableInMemoryRepository {

  def apply[F[_]: Effect](
      sourceTable: FeaturesTableInMemoryRepository[F],
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable],
      extendedTableNumber: Int
  ): Validation[Resource[F, ExtendedFeaturesTableInMemoryRepository[F]]] = {
    val pk = sourceTable.table.primaryKey.headOption
      .getOrElse(
        throw new Exception(
          s"Expected a primary key with one column in table ${sourceTable.table.table.toString}"
        )
      )
    val rndColumn       = TableColumn("_rnd", SqlType.numeric)
    val newColumns      = newFeatures ++ otherColumns
    val extTableColumns = newFeatures ++ List(rndColumn)
    val extViewColumns  = newColumns ++ extTableColumns.filter(_ != pk)
    val table           = sourceTable.table
    val extTable = table.copy(
      table = table.table.copy(name = s"${table.table.name}__$extendedTableNumber"),
      validateSchema = false
    )
    val extView = extTable.copy(table = extTable.table.copy(name = extTable.table.name + "v"))
    val repository = new ExtendedFeaturesTableInMemoryRepository[F](
      sourceTable,
      filters,
      extView,
      extViewColumns,
      extTable,
      newFeatures,
      otherColumns,
      rndColumn,
      prefills
    )

    Resource.make(Effect[F].delay(repository))(_ => Effect[F].delay(())).valid
  }

}

class ExtendedFeaturesTableInMemoryRepository[F[_]: Effect] private (
    val sourceTable: FeaturesTableInMemoryRepository[F],
    val filters: Option[FilterRule],
    val view: FeaturesTableDescription,
    val viewColumns: List[TableColumn],
    val extTable: FeaturesTableDescription,
    val newFeatures: List[TableColumn],
    val otherColumns: List[TableColumn],
    val rndColumn: TableColumn,
    val prefills: List[PrefillExtendedFeaturesTable]
) extends FeaturesTableRepository[F] {

  /**
    * Description of the table
    *
    * @return the description
    */
  override def table: FeaturesTableDescription = view

  /**
    * Total number of rows in the table
    *
    * @return number of rows
    */
  override def count: F[Int] = sourceTable.count

  /**
    * Number of rows belonging to the dataset.
    *
    * @param dataset The dataset used to filter rows
    * @return the number of rows in the dataset, 0 if dataset is not associated with the table
    */
  override def count(dataset: DatasetId): F[Int] = sourceTable.count(dataset)

  /**
    * Number of rows matching the filters.
    *
    * @param filters The filters used to filter rows
    * @return the number of rows in the dataset matching the filters, or the total number of rows if there are no filters
    */
  override def count(filters: Option[FilterRule]): F[Int] = sourceTable.count(filters)

  /**
    * Number of rows grouped by a reference column
    *
    * @return a map containing the number of rows for each value of the group by column
    */
  override def countGroupBy(groupByColumn: TableColumn,
                            filters: Option[FilterRule]): F[Map[String, Int]] =
    // Simple heuristic to regenerate the windows created by KFoldFeaturesSplitter
    if (otherColumns.contains(groupByColumn)) {
      prefills
        .flatMap {
          case p: KFoldFeaturesSplitterDefinition => Some(p.numFolds)
          case _: FeaturesSplitterDefinition      => None
        }
        .map { numFolds =>
          count(filters).map { total =>
            Range.inclusive(1, numFolds).map(f => f.toString -> total / numFolds).toMap
          }
        }
        .head
    } else {
      sourceTable.countGroupBy(groupByColumn, filters)
    }

  /**
    * @return all headers of the table
    */
  override def columns: Headers = viewColumns

  override def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])] =
    sourceTable.features(query)

  /**
    *
    * @param filters      Filters always applied on the queries
    * @param newFeatures  New features to create, can be used in machine learning tasks
    * @param otherColumns Other columns to create, present to support some internal functionalities like cross-validation
    * @param prefills     List of generators for update statements used to pre-fill the extended features table with data.
    * @return
    */
  override def createExtendedFeaturesTable(
      filters: Option[FilterRule],
      newFeatures: List[TableColumn],
      otherColumns: List[TableColumn],
      prefills: List[PrefillExtendedFeaturesTable],
      extendedTableNumber: Int
  ): Validation[Resource[F, FeaturesTableRepository[F]]] =
    "Impossible to extend an extended table".invalidNel

  override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])

  /**
    * Returns the list of datasets effectively used by a query
    *
    * @param filters The filters used to filter rows
    * @return a set of dataset ids
    */
  override def datasets(filters: Option[FilterRule]): F[Set[DatasetId]] =
    sourceTable.datasets(filters)
}
