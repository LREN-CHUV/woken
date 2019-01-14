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

package ch.chuv.lren.woken.mining

import java.util.UUID
import cats.implicits._
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableId }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableId
import ch.chuv.lren.woken.Predefined.Algorithms.{
  anovaDefinition,
  anovaFactorial,
  knnDefinition,
  knnWithK5
}

object ExperimentQuerySupport {

  def experimentQuery(algorithm: String, parameters: List[CodeValue]) =
    ExperimentQuery(
      user = UserId("test1"),
      variables = List(VariableId("cognitive_task2")),
      covariables = List(VariableId("score_test1"), VariableId("college_math")),
      covariablesMustExist = false,
      grouping = Nil,
      filters = None,
      targetTable = Some("Sample"),
      algorithms = List(AlgorithmSpec(algorithm, parameters, None)),
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = Set(),
      testingDatasets = Set(),
      validationDatasets = Set(),
      executionPlan = None
    )

  def experimentQuery(algorithms: List[AlgorithmSpec]) =
    ExperimentQuery(
      user = UserId("test1"),
      variables = List(VariableId("cognitive_task2")),
      covariables = List(VariableId("score_test1"), VariableId("college_math")),
      covariablesMustExist = false,
      grouping = Nil,
      filters = None,
      targetTable = Some("Sample"),
      algorithms = algorithms,
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = Set(),
      testingDatasets = Set(),
      validationDatasets = Set(),
      executionPlan = None
    )

  def experimentQuery2job(query: ExperimentQuery): Validation[ExperimentActor.Job] =
    ExperimentJob.mkValid(
      UUID.randomUUID().toString,
      query,
      FeaturesTableDescription(TableId("features_db", None, query.targetTable.getOrElse("Sample")),
                               Nil,
                               None,
                               validateSchema = false,
                               None,
                               0.67),
      Nil, { spec =>
        Map(knnWithK5 -> knnDefinition, anovaFactorial -> anovaDefinition)
          .get(spec)
          .toRight("Missing algorithm")
          .toValidatedNel[String]
      }
    )

}
