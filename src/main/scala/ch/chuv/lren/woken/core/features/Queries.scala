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

import ch.chuv.lren.woken.messages.query.Query
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.query.filters.FilterRule._
import ch.chuv.lren.woken.messages.variables.{ FeatureIdentifier, VariableId }

case class QueryOffset(start: Int, count: Int) {
  def end: Int = start + count
}

object Queries {

  // TODO: add support for GroupId as feature
  implicit class QueryEnhanced(val query: Query) extends AnyVal {

    /** Convert variable to lowercase as Postgres returns lowercase fields in its result set
      * Variables codes are sanitized to ensure valid database field names using the following conversions:
      * + replace - by _
      * + prepend _ to the variable name if it starts by a number
      */
    private[this] def toField(feature: FeatureIdentifier) = feature match {
      case v: VariableId => v.code.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1")
      case _             => throw new NotImplementedError("Need to add support for groups as a feature")
    }

    def dbAllVars: List[String] = (dbVariables ++ dbCovariables ++ dbGrouping).distinct

    def dbVariables: List[String]   = query.variables.map(toField)
    def dbCovariables: List[String] = query.covariables.map(toField)
    def dbGrouping: List[String]    = query.grouping.map(toField)

    def features(defaultInputTable: String,
                 excludeNullValues: Boolean,
                 shadowOffset: Option[QueryOffset]): FeaturesQuery = {

      val inputTable        = query.targetTable.getOrElse(defaultInputTable)
      val nonNullableFields = if (excludeNullValues) query.dbAllVars else query.dbVariables
      val notNullFilters: List[FilterRule] = nonNullableFields
        .map(v => SingleFilterRule(v, v, "string", InputType.text, Operator.isNotNull, Nil))
      val mergingQueryFilters =
        query.filters.map(_.withAdaptedFieldName).fold(notNullFilters)(f => notNullFilters :+ f)
      val filters: FilterRule = mergingQueryFilters match {
        case List(f) => f
        case _       => CompoundFilterRule(Condition.and, mergingQueryFilters)
      }

      val selectNoPaging =
        s"SELECT ${query.dbAllVars.map(_.identifier).mkString(",")} FROM $inputTable WHERE ${filters.toSqlWhere}"

      val sqlQuery = shadowOffset.fold(selectNoPaging) { o =>
        selectNoPaging + s" EXCEPT ALL (" + selectNoPaging + s" OFFSET ${o.start} LIMIT ${o.count})"
      }

      FeaturesQuery(dbVariables, dbCovariables, dbGrouping, inputTable, sqlQuery)
    }
  }

}
