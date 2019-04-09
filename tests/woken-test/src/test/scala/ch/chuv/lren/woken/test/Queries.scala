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

package ch.chuv.lren.woken.test

import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableId
import ch.chuv.lren.woken.messages.datasets.TableId

import scala.collection.immutable.TreeSet

trait Queries {

  val sampleTable: TableId = tableId("sample_data")
  val cdeFeaturesATableId: TableId = tableId("cde_features_a")
  val cdeFeaturesBTableId: TableId = tableId("cde_features_b")
  val cdeFeaturesCTableId: TableId = tableId("cde_features_c")
  val cdeFeaturesMixedTableId: TableId = tableId("mip_cde_features")

  def experimentQuery(
      algorithm: String,
      parameters: List[CodeValue],
      variables: List[VariableId] = List(VariableId("cognitive_task2")),
      covariables: List[VariableId] =
        List(VariableId("score_test1"), VariableId("college_math")),
      targetTable: Option[TableId] = Some(sampleTable)): Query =
    multipleExperimentQuery(algorithms =
                              List(AlgorithmSpec(algorithm, parameters, None)),
                            variables = variables,
                            covariables = covariables,
                            targetTable = targetTable)

  def multipleExperimentQuery(
      algorithms: List[AlgorithmSpec],
      variables: List[VariableId] = List(VariableId("cognitive_task2")),
      covariables: List[VariableId] =
        List(VariableId("score_test1"), VariableId("college_math")),
      targetTable: Option[TableId] = Some(sampleTable)): Query =
    ExperimentQuery(
      user = UserId("test1"),
      variables = variables,
      covariables = covariables,
      covariablesMustExist = true,
      grouping = Nil,
      filters = None,
      targetTable = targetTable,
      algorithms = algorithms,
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = TreeSet(),
      testingDatasets = TreeSet(),
      validationDatasets = TreeSet(),
      executionPlan = None
    )

  private def tableId(name: String) = TableId("features", name)

}
