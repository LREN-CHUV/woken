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

import cats.data.{ NonEmptyList, Validated }
import cats.effect.{ Effect, Resource }
import cats.implicits._
import ch.chuv.lren.woken.config.DatabaseConfiguration
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.datasets.{ DatasetId, TableId }
import ch.chuv.lren.woken.messages.query.{ UserFeedback, UserFeedbacks, UserWarning }
import spray.json._
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import doobie.util.log.LogHandler
import doobie.util.update.Update0

import scala.language.higherKinds

/**
  * The interface to Features database
  *
  * @tparam F The monad wrapping the connection to the database
  */
trait FeaturesRepository[F[_]] extends Repository[F] {

  /**
    * @return the database as defined in the configuration
    */
  def database: DatabaseConfiguration

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
    * Validate the fields in the actual table against their metadata
    *
    * @param variables Full list of variables for the table as defined in the metadata
    */
  def validateFields(variables: List[VariableMetaData])(
      implicit effect: Effect[F]
  ): F[Validated[NonEmptyList[(VariableMetaData, UserFeedback)], UserFeedbacks]] = {
    val variableNames = variables.map(_.toId.code).toSet
    val headerNames   = columns.map(_.name).toSet
    val unknownHeaders =
      headerNames.diff(variableNames).filterNot(c => table.primaryKey.exists(_.name == c))
    val unknownVariables = variableNames.diff(headerNames)
    unknownVariables.toList.toNel
      .fold(
        unknownHeaders
          .map { h =>
            val msg = s"Column $h in table ${table.table.toString} is not described in the metadata"
            UserWarning(msg): UserFeedback
          }
          .toList
          .valid[NonEmptyList[(VariableMetaData, UserFeedback)]]
      )(
        unknowVarsNel =>
          unknowVarsNel
            .map(
              v =>
                variables
                  .find(_.code == v)
                  .getOrElse(throw new IllegalStateException("This variable should exist"))
            )
            .map(
              varMeta =>
                (varMeta,
                 UserWarning(
                   s"Variable ${varMeta.code} does not exist in table ${table.table.toString}"
                 ))
            )
            .invalid
      )
      .pure[F]
  }

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
      prefills: List[PrefillExtendedFeaturesTable],
      extendedTableNumber: Int
  ): Validation[Resource[F, FeaturesTableRepository[F]]]

}

object FeaturesTableRepository {

  type Headers = List[TableColumn]

}
