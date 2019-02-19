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

import java.util.UUID

import ch.chuv.lren.woken.core.model.database.FeaturesTableDescription
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.Predefined.Algorithms.{
  anovaDefinition,
  anovaFactorial,
  knnDefinition,
  knnWithK5
}
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.messages.variables.VariableId
import ch.chuv.lren.woken.Predefined.FeaturesTable._

import cats.implicits._
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }

import org.scalatest.{ Matchers, WordSpec }
import scala.collection.immutable.TreeSet

class ExperimentJobTest extends WordSpec with Matchers with ValidatedMatchers with ValidatedValues {

  val user: UserId = UserId("test")

  "Experiment job" should {

    "fail in case of a query with no algorithms" in {
      val experimentQuery = ExperimentQuery(
        user = user,
        variables = Nil,
        covariables = Nil,
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = None,
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        algorithms = Nil,
        validations = Nil,
        executionPlan = None
      )
      val experimentJob = experimentQuery2job(experimentQuery)
      experimentJob.isValid shouldBe false
      experimentJob.invalidValue.head shouldBe "No algorithm defined"
      experimentJob.invalidValue.size shouldBe 1
    }

    "fail in case of a query containing an invalid algorithm" in {
      val experiment    = experimentQuery("invalid-algo", Nil)
      val experimentJob = experimentQuery2job(experiment)
      experimentJob.isValid shouldBe false
      experimentJob.invalidValue.head shouldBe "Missing algorithm"
      experimentJob.invalidValue.size shouldBe 1
    }

  }

  def experimentQuery(algorithm: String, parameters: List[CodeValue]) =
    ExperimentQuery(
      user = UserId("test1"),
      variables = List(VariableId("cognitive_task2")),
      covariables = List(VariableId("score_test1"), VariableId("college_math")),
      covariablesMustExist = false,
      grouping = Nil,
      filters = None,
      targetTable = Some(sampleDataTableId),
      algorithms = List(AlgorithmSpec(algorithm, parameters, None)),
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = TreeSet(),
      testingDatasets = TreeSet(),
      validationDatasets = TreeSet(),
      executionPlan = None
    )

  private def experimentQuery2job(query: ExperimentQuery): Validation[ExperimentJob] = {
    val targetTableId = query.targetTable.getOrElse(sampleDataTableId)
    val targetTable: FeaturesTableDescription = targetTableId match {
      case `sampleDataTableId`   => sampleTable
      case `cdeFeaturesATableId` => cdeTable
      case o                     => throw new NotImplementedError(s"$o is not supported")
    }
    ExperimentJob.mkValid(
      UUID.randomUUID().toString,
      query,
      targetTable,
      Nil, { spec =>
        Map(knnWithK5 -> knnDefinition, anovaFactorial -> anovaDefinition)
          .get(spec)
          .toRight("Missing algorithm")
          .toValidatedNel[String]
      }
    )
  }

}
