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

package ch.chuv.lren.woken.core.features

import ch.chuv.lren.woken.core.model.TableColumn
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.query.filters.FilterRule._
import ch.chuv.lren.woken.messages.variables.SqlType

sealed trait Sampling

case class LeaveOutPartition(folds: Integer, excludeFold: Integer, orderColumn: Option[TableColumn])
    extends Sampling {
  assert(excludeFold < folds)
}

/**
  * A query for selecting data features in a table
  *
  * @param dbVariables List of variables (dependent features)
  * @param dbCovariables List of covariables (independent features)
  * @param dbGrouping List of fields to use in a group by statement (or equivalent when an algorithm supporting grouping is used)
  * @param dbTable Database table containing the data
  * @param filters Filters to apply on the data
  * @param sampling Sampling method used to select
  */
case class FeaturesQuery(
    dbVariables: List[String],
    dbCovariables: List[String],
    dbGrouping: List[String],
    dbTable: String,
    filters: Option[FilterRule],
    sampling: Option[Sampling]
) {

  def dbAllVars: List[String] = (dbVariables ++ dbCovariables ++ dbGrouping).distinct

  /**
    * Full SQL query, ready for execution
    *
    * @return a SQL query
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def sql: String =
    sampling match {
      case None => selectFiltered(selectOnly, filters)

      case Some(LeaveOutPartition(folds, excludeFold, Some(orderColumn)))
          if orderColumn.sqlType == SqlType.char || orderColumn.sqlType == SqlType.varchar =>
        selectExcludingFold(excludeFold, windowOrderByStrCol(folds, orderColumn))

      case Some(LeaveOutPartition(folds, excludeFold, Some(orderColumn)))
          if orderColumn.sqlType == SqlType.int =>
        selectExcludingFold(excludeFold, windowOrderByIntCol(folds, orderColumn))

      case Some(LeaveOutPartition(folds, excludeFold, None)) =>
        s"SELECT ${selectExcludingFold(excludeFold, windowOrderRandom(folds))}"

      case _ => throw new NotImplementedError(s"Unhandled sampling $sampling")
    }

  private def selectFields = s"SELECT ${dbAllVars.map(_.identifier).mkString(",")}"

  private def selectOnly = s"""$selectFields FROM "$dbTable""""

  private def selectFiltered(select: String, filters: Option[FilterRule]) =
    filters.fold(selectOnly) { filters =>
      s"$select WHERE ${filters.withAdaptedFieldName.toSqlWhere}"
    }

  private def windowOrderByStrCol(folds: Integer, orderColumn: TableColumn): String =
    s"""ntile($folds) over (order by abs(('x'||substr(md5("${orderColumn.name}"),1,16))::bit(64)::BIGINT)) as "_window_""""

  // Requires pseudo_encrypt function - https://wiki.postgresql.org/wiki/Pseudo_encrypt
  private def windowOrderByIntCol(folds: Integer, orderColumn: TableColumn): String =
    s"""ntile($folds) over (order by pseudo_encrypt("${orderColumn.name}")) as "_window_""""

  private def windowOrderRandom(folds: Integer): String =
    s"""ntile($folds) over (order by random()) as "_window_""""

  private def filtersExcludingWindow(excludeWindow: Integer): FilterRule = filters match {
    case None =>
      excludWindowRule(excludeWindow)
    case Some(rule) =>
      CompoundFilterRule(Condition.and, List(rule, excludWindowRule(excludeWindow)))
  }

  private def excludWindowRule(excludeWindow: Integer) =
    SingleFilterRule("_window_",
                     "_window_",
                     "int",
                     InputType.number,
                     Operator.notEqual,
                     List(s"$excludeWindow"))

  private def selectExcludingFold(excludeFold: Integer, windowRowDefinition: String): String = {
    val filtersExcludingFold = filtersExcludingWindow(excludeFold + 1)
    selectFiltered(s"""$selectFields,$windowRowDefinition FROM "$dbTable"""",
                   Some(filtersExcludingFold))
  }

}
