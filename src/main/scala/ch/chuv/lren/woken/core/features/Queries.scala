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

import ch.chuv.lren.woken.core.model.database.{ TableColumn, TableId }
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, Query }
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.{ FeatureIdentifier, VariableId }

case class QueryOffset(start: Int, count: Int) {
  def end: Int = start + count
}

object Queries {

  /** Convert variable to lowercase as Postgres returns lowercase fields in its result set
    * Variables codes are sanitized to ensure valid database field names using the following conversions:
    * + replace - by _
    * + prepend _ to the variable name if it starts by a number
    */
  def toField(feature: FeatureIdentifier): String = feature match {
    case v: VariableId => v.code.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1")
    // TODO: add support for groups as a feature
    case _ => throw new NotImplementedError("Need to add support for groups as a feature")
  }

  // TODO: add support for GroupId as feature
  implicit class QueryEnhanced[Q <: Query](val query: Q) extends AnyVal {

    def allVars: List[VariableId] =
      (query.variables ++ query.covariables ++ query.grouping).distinct.flatMap {
        case v @ VariableId(_) => Some(v)
        // TODO: expend a group into a list of variables
        case _ => None
      }

    def dbAllVars: List[String] = (dbVariables ++ dbCovariables ++ dbGrouping).distinct

    def dbVariables: List[String]   = query.variables.map(toField)
    def dbCovariables: List[String] = query.covariables.map(toField)
    def dbGrouping: List[String]    = query.grouping.map(toField)

    /**
      * Add a filter to remove null values, either partially, where rows containing null values in the target variables are excluded,
      * or totally, where rows containing a null value in any field used by the query are excluded.
      *
      * @param variablesCanBeNull If false, target variables containing null values are excluded
      * @param covariablesCanBeNull If false, covariables and grouping fields containing null values are excluded
      */
    def filterNulls(variablesCanBeNull: Boolean, covariablesCanBeNull: Boolean): Q = {
      val nonNullableFields = (if (variablesCanBeNull) List() else query.dbVariables) ++
        (if (covariablesCanBeNull) List() else query.dbCovariables ++ query.dbGrouping).distinct
      if (nonNullableFields.isEmpty)
        query
      else {
        val notNullFilters: List[FilterRule] = nonNullableFields
          .map(v => SingleFilterRule(v, v, "string", InputType.text, Operator.isNotNull, Nil))
        val mergingQueryFilters =
          query.filters.fold(notNullFilters)(f => notNullFilters :+ f)
        val filters: FilterRule = mergingQueryFilters match {
          case List(f) => f
          case _       => CompoundFilterRule(Condition.and, mergingQueryFilters)
        }
        setFilters(filters)
      }
    }

    /**
      * Add a filter that returns only the rows that belong to one dataset defined in dataset property.
      *
      * If the list of datasets is empty, this method does nothing.
      */
    def filterDatasets(datasetColumn: Option[TableColumn]): Q = {
      val datasets: Set[DatasetId] = query match {
        case q: MiningQuery     => q.datasets
        case q: ExperimentQuery => q.trainingDatasets
      }

      if (datasets.isEmpty) query
      else {
        //
        datasetColumn.fold(query) { dsCol =>
          val datasetsFilter = SingleFilterRule(dsCol.name,
                                                dsCol.name,
                                                "string",
                                                InputType.text,
                                                Operator.in,
                                                datasets.map(_.code).toList)
          val filters: FilterRule =
            query.filters
              .fold(datasetsFilter: FilterRule)(
                f => CompoundFilterRule(Condition.and, List(datasetsFilter, f))
              )

          setFilters(filters)
        }
      }
    }

    def features(defaultInputTable: TableId, orderBy: Option[String]): FeaturesQuery = {
      val inputTable =
        query.targetTable.fold(defaultInputTable)(t => defaultInputTable.copy(name = t))

      FeaturesQuery(dbVariables,
                    dbCovariables,
                    dbGrouping,
                    inputTable,
                    query.filters,
                    None,
                    orderBy)
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    private def setFilters(filters: FilterRule): Q =
      query match {
        case q: MiningQuery     => q.copy(filters = Some(filters)).asInstanceOf[Q]
        case q: ExperimentQuery => q.copy(filters = Some(filters)).asInstanceOf[Q]
      }

  }

}
