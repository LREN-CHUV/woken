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

package ch.chuv.lren.woken.core.model.jobs

import cats.implicits._
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableId }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, ExperimentQuery }
import ch.chuv.lren.woken.messages.variables.VariableMetaData

case class ExperimentJob(
    override val jobId: String,
    inputTable: TableId,
    override val query: ExperimentQuery,
    queryAlgorithms: Map[AlgorithmSpec, AlgorithmDefinition],
    metadata: List[VariableMetaData]
) extends Job[ExperimentQuery]

object ExperimentJob {

  def mkValid(
      jobId: String,
      query: ExperimentQuery,
      inputTable: FeaturesTableDescription,
      metadata: List[VariableMetaData],
      algorithmsLookup: AlgorithmSpec => Validation[AlgorithmDefinition]
  ): Validation[ExperimentJob] =
    query.algorithms
      .map(a => algorithmsLookup(a).map(defn => a -> defn))
      .sequence[Validation, (AlgorithmSpec, AlgorithmDefinition)]
      .andThen { algorithms =>
        if (algorithms.isEmpty) "No algorithm defined".invalidNel else algorithms.validNel
      }
      .andThen { algorithms =>
        if (algorithms.exists(_._2.predictive) && query.validations.nonEmpty &&
            query.validations
              .flatMap(_.parametersAsMap.get("k"))
              .map(Integer.parseInt)
              .exists(_ < 2)) {
          "When a predictive algorithm is used with cross validation, parameter k must be 2 or more".invalidNel
        } else algorithms.validNel
      }
      .map(_.toMap)
      .map { algorithms =>
        // Select the dataset common between all algorithms. If one algorithms cannot handle nulls, all nulls must be ignored
        // This restrict the set of features we can learn from in some cases. User is warned about this case and
        // can correct his selection of ML algorithms
        val variablesCanBeNull = algorithms.exists { case (_, defn) => defn.variablesCanBeNull }
        val allRequireVariablesNotNull = !variablesCanBeNull && !algorithms.exists {
          case (_, defn) => defn.variablesCanBeNull
        }
        val covariablesCanBeNull = algorithms.exists {
          case (_, defn) => defn.covariablesCanBeNull
        }
        val allRequireCovariablesNotNull = !covariablesCanBeNull && !algorithms.exists {
          case (_, defn) => defn.covariablesCanBeNull
        }
        val experimentQuery = query
          .filterDatasets(inputTable.datasetColumn)
          .filterNulls(variablesCanBeNull, covariablesCanBeNull)
          .copy(targetTable = Some(inputTable.table.name))

        // TODO: report to user filtering on nulls when activated
        // TODO: report to user which algorithms are causing filter on nulls, and how many records are lost from training dataset

        new ExperimentJob(jobId,
                          inputTable.table,
                          experimentQuery,
                          queryAlgorithms = algorithms,
                          metadata = metadata)
      }
}
