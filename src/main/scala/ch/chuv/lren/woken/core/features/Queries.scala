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

import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, Query }
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.query.filters.FilterRule._
import ch.chuv.lren.woken.messages.variables.{ FeatureIdentifier, VariableId }

case class QueryOffset(start: Int, count: Int) {
  def end: Int = start + count
}

object Queries {

  // TODO: add support for GroupId as feature
  implicit class QueryEnhanced[Q <: Query](val query: Q) extends AnyVal {

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

    /**
      * Add a filter to remove null values, either partially, where rows containing null values in the target variables are excluded,
      * or totally, where rows containing a null value in any field used by the query are excluded.
      *
      * @param excludeAllNulls If true, all fields containing null values are excluded, otherwise only the target variables are excluded
      */
    def filterNulls(excludeAllNulls: Boolean): Q = {
      val nonNullableFields = if (excludeAllNulls) query.dbAllVars else query.dbVariables
      val notNullFilters: List[FilterRule] = nonNullableFields
        .map(v => SingleFilterRule(v, v, "string", InputType.text, Operator.isNotNull, Nil))
      val mergingQueryFilters =
        query.filters.fold(notNullFilters)(f => notNullFilters :+ f)
      val filters: FilterRule = mergingQueryFilters match {
        case List(f) => f
        case _       => CompoundFilterRule(Condition.and, mergingQueryFilters)
      }
      query match {
        case q: MiningQuery     => q.copy(filters = Some(filters)).asInstanceOf[Q]
        case q: ExperimentQuery => q.copy(filters = Some(filters)).asInstanceOf[Q]
      }
    }

    /**
      * Add a filter that returns only the rows that belong to one dataset defined in dataset property.
      */
    def filterDatasets: Q = {
      val datasets: Set[DatasetId] = query match {
        case q: MiningQuery     => q.datasets
        case q: ExperimentQuery => q.trainingDatasets
      }

      val datasetsFilter = SingleFilterRule("dataset",
                                            "dataset",
                                            "string",
                                            InputType.text,
                                            Operator.in,
                                            datasets.map(_.code).toList)
      val filters: FilterRule =
        query.filters
          .fold(datasetsFilter: FilterRule)(
            f => CompoundFilterRule(Condition.and, List(datasetsFilter, f))
          )

      query match {
        case q: MiningQuery     => q.copy(filters = Some(filters)).asInstanceOf[Q]
        case q: ExperimentQuery => q.copy(filters = Some(filters)).asInstanceOf[Q]
      }
    }

    /**
      * Returns the database query for the selection of data features for training algorithms or mining data
      *
      * @param defaultInputTable The input table to use by default if no value is defined in the query
      * @param offset Offset for the selection of data
      * @return the database query for the selection of data features
      */
    def features(defaultInputTable: String, offset: Option[QueryOffset]): FeaturesQuery = {

      val inputTable = query.targetTable.getOrElse(defaultInputTable)

      val selectOnly =
        s"SELECT ${query.dbAllVars.map(_.identifier).mkString(",")} FROM $inputTable"

      val selectFiltered = query.filters.fold(selectOnly) { filters =>
        s"$selectOnly WHERE ${filters.withAdaptedFieldName.toSqlWhere}"
      }

      val sqlQuery = offset.fold(selectFiltered) { o =>
        s"$selectFiltered EXCEPT ALL ($selectFiltered OFFSET ${o.start} LIMIT ${o.count})"
      }

      FeaturesQuery(dbVariables, dbCovariables, dbGrouping, inputTable, sqlQuery)
    }
  }

}
