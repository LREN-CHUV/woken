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

package ch.chuv.lren.woken

import java.time.OffsetDateTime

import cats.scalatest.ValidatedValues
import ch.chuv.lren.woken.core.model.{ AlgorithmDefinition, AlgorithmEngine, VariablesMeta }
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.core.model.jobs.{
  DockerJob,
  JsonDataJobResult,
  PfaJobResult,
  ValidationJob
}
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.variables.{ GroupMetaData, VariableId }
import ch.chuv.lren.woken.messages.variables.variablesProtocol._
import spray.json._

import scala.collection.immutable.TreeSet

/**
  * Some predefined objects to be used in the tests
  */
object Predefined {

  object Users {
    val user1: UserId = UserId("test1")
  }

  object Algorithms {

    val knnWithK5: AlgorithmSpec = AlgorithmSpec(
      code = "knn",
      parameters = List(CodeValue("k", "5")),
      step = None
    )

    val knnDefinition = AlgorithmDefinition(
      "knn",
      "hbpmip/python-knn",
      predictive = true,
      variablesCanBeNull = false,
      covariablesCanBeNull = false,
      engine = AlgorithmEngine.Docker,
      distributedExecutionPlan = ExecutionPlan.scatterGather
    )

    val anovaFactorial = AlgorithmSpec("anova", List(CodeValue("design", "factorial")), None)

    val anovaDefinition = AlgorithmDefinition(
      "anova",
      "hbpmip/python-anova",
      predictive = false,
      variablesCanBeNull = false,
      covariablesCanBeNull = false,
      engine = AlgorithmEngine.Docker,
      distributedExecutionPlan = ExecutionPlan.scatterGather
    )
  }

  object Filters {

    val apoe4LeftHippDesdFilter = Some(
      CompoundFilterRule(
        Condition.and,
        List(
          SingleFilterRule("apoe4", "apoe4", "string", InputType.text, Operator.isNotNull, List()),
          SingleFilterRule("lefthippocampus",
                           "lefthippocampus",
                           "string",
                           InputType.text,
                           Operator.isNotNull,
                           List()),
          SingleFilterRule("dataset",
                           "dataset",
                           "string",
                           InputType.text,
                           Operator.in,
                           List("desd-synthdata"))
        )
      )
    )

    val scoreStressTest1Filter = Some(
      CompoundFilterRule(
        Condition.and,
        List(
          SingleFilterRule("score_test1",
                           "score_test1",
                           "string",
                           InputType.text,
                           Operator.isNotNull,
                           List()),
          SingleFilterRule("stress_before_test1",
                           "stress_before_test1",
                           "string",
                           InputType.text,
                           Operator.isNotNull,
                           List())
        )
      )
    )

  }

  object ExperimentQueries {
    import Users._
    import Algorithms._

    def sampleExperimentQuery(algorithms: List[AlgorithmSpec]) =
      ExperimentQuery(
        user = user1,
        variables = List(VariableId("cognitive_task2")),
        covariables = List(VariableId("score_test1"), VariableId("college_math")),
        covariablesMustExist = false,
        grouping = Nil,
        filters = None,
        targetTable = Some(sampleDataTableId),
        algorithms = algorithms,
        validations = List(ValidationSpec("kfold", List(CodeValue("k", "3")))),
        trainingDatasets = TreeSet(),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        executionPlan = None
      )

    def sampleExperimentQuery(algorithm: String, parameters: List[CodeValue]): ExperimentQuery =
      sampleExperimentQuery(List(AlgorithmSpec(algorithm, parameters, None)))

    val knnAlgorithmQuery: ExperimentQuery   = sampleExperimentQuery(List(knnWithK5))
    val anovaAlgorithmQuery: ExperimentQuery = sampleExperimentQuery(List(anovaFactorial))

    val unknownTableQuery: ExperimentQuery =
      knnAlgorithmQuery.copy(targetTable = Some(unknownTableId))

    val unknownAlgorithmQuery: ExperimentQuery = sampleExperimentQuery("unknown", Nil)

    val unknownTargetVariableQuery: ExperimentQuery =
      knnAlgorithmQuery.copy(variables = List(VariableId("unknown")))

    val allCovariablesUnknownQuery: ExperimentQuery =
      knnAlgorithmQuery.copy(covariables = List(VariableId("unknown1"), VariableId("unknown2")))

    val missingRequiredCovariableQuery: ExperimentQuery =
      knnAlgorithmQuery.copy(covariables =
                               List(VariableId("stress_before_test1"), VariableId("unknown")),
                             covariablesMustExist = true)

    val noAlgorithmQuery: ExperimentQuery = sampleExperimentQuery(Nil)

    def cdeExperimentQuery(algorithm: String, parameters: List[CodeValue]) =
      ExperimentQuery(
        user = user1,
        variables = List(VariableId("apoe4")),
        covariables = List(VariableId("lefthippocampus")),
        covariablesMustExist = true,
        grouping = Nil,
        filters = None,
        targetTable = Some(cdeFeaturesATableId),
        algorithms = List(AlgorithmSpec(algorithm, parameters, None)),
        validations = List(ValidationSpec("kfold", List(CodeValue("k", "3")))),
        trainingDatasets = TreeSet(DatasetId("desd-synthdata")),
        testingDatasets = TreeSet(),
        validationDatasets = TreeSet(),
        executionPlan = None
      )

    val knnAlgorithmCdeQuery: ExperimentQuery = cdeExperimentQuery("knn", List(CodeValue("k", "5")))

    val withUnknownCovariablesQuery: ExperimentQuery = knnAlgorithmCdeQuery.copy(
      covariables = List(VariableId("lefthippocampus"), VariableId("unknown")),
      covariablesMustExist = false
    )

    val validationQuery: ExperimentQuery = knnAlgorithmQuery.copy(
      algorithms = List(AlgorithmSpec(ValidationJob.algorithmCode, Nil, None))
    )
  }

  object MiningQueries {
    import Users._

    val knnAlgorithmQuery = MiningQuery(
      user = user1,
      variables = List(VariableId("score_test1")),
      covariables = List(VariableId("stress_before_test1")),
      covariablesMustExist = false,
      grouping = Nil,
      filters = None,
      targetTable = Some(sampleDataTableId),
      algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
      datasets = TreeSet(DatasetId("Sample")),
      executionPlan = None
    )

    val unknownTableQuery: MiningQuery =
      knnAlgorithmQuery.copy(targetTable = Some(unknownTableId))

    val unknownAlgorithmQuery: MiningQuery =
      knnAlgorithmQuery.copy(algorithm = AlgorithmSpec("unknown", Nil, None))

    val unknownTargetVariableQuery: MiningQuery =
      knnAlgorithmQuery.copy(variables = List(VariableId("unknown")))

    val allCovariablesUnknownQuery: MiningQuery =
      knnAlgorithmQuery.copy(covariables = List(VariableId("unknown1"), VariableId("unknown2")))

    val missingRequiredCovariableQuery: MiningQuery =
      knnAlgorithmQuery.copy(covariables =
                               List(VariableId("stress_before_test1"), VariableId("unknown")),
                             covariablesMustExist = true)

    val queryTableWithoutDatasetColumn: MiningQuery =
      knnAlgorithmQuery.copy(covariablesMustExist = true)

    val knnOnTableWithDatasetQuery = MiningQuery(
      user = user1,
      variables = List(VariableId("apoe4")),
      covariables = List(VariableId("lefthippocampus")),
      covariablesMustExist = true,
      grouping = Nil,
      filters = None,
      targetTable = Some(cdeFeaturesATableId),
      algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5")), None),
      datasets = TreeSet(DatasetId("desd-synthdata")),
      executionPlan = None
    )

    val queryWithSomeUnknownCovariables: MiningQuery =
      knnOnTableWithDatasetQuery.copy(covariables =
                                        List(VariableId("lefthippocampus"), VariableId("unknown")),
                                      covariablesMustExist = false)

    val validationQuery: MiningQuery = knnOnTableWithDatasetQuery.copy(
      algorithm = AlgorithmSpec(ValidationJob.algorithmCode, Nil, None)
    )
  }

  object VariablesMetas extends JsonUtils {
    val churnHierarchy: GroupMetaData = loadJson("/metadata/churn_variables.json")
      .convertTo[GroupMetaData]
    val churnVariablesMeta: VariablesMeta = new VariablesMeta(
      1,
      "churn",
      churnHierarchy,
      churnDataTableId,
      List("state", "custserv_calls", "churn").map(VariableId)
    )

    val sampleHierarchy: GroupMetaData = loadJson("/metadata/sample_variables.json")
      .convertTo[GroupMetaData]
    val sampleVariablesMeta: VariablesMeta =
      VariablesMeta(2, "sample", sampleHierarchy, sampleDataTableId, Nil)

    val cdeHierarchy: GroupMetaData = loadJson("/metadata/mip_cde_variables.json")
      .convertTo[GroupMetaData]
    val cdeGroupings: List[VariableId] =
      List("dataset", "gender", "agegroup", "alzheimerbroadcategory").map(VariableId)
    val featuresAVariablesMeta: VariablesMeta =
      VariablesMeta(3, "cde_features_a", cdeHierarchy, cdeFeaturesATableId, cdeGroupings)
    val featuresBVariablesMeta: VariablesMeta =
      VariablesMeta(4, "cde_features_b", cdeHierarchy, cdeFeaturesBTableId, cdeGroupings)
    val featuresCVariablesMeta: VariablesMeta =
      VariablesMeta(5, "cde_features_c", cdeHierarchy, cdeFeaturesCTableId, cdeGroupings)
    val featuresMixedVariablesMeta: VariablesMeta =
      VariablesMeta(6, "cde_features_mixed", cdeHierarchy, cdeFeaturesMixedTableId, cdeGroupings)

  }

  object FeaturesTable {
    val churnTable =
      FeaturesTableDescription(churnDataTableId, Nil, None, validateSchema = false, None, 0.67)
    val churnHeaders = List(
      TableColumn("state", SqlType.char),
      TableColumn("account_length", SqlType.int),
      TableColumn("area_code", SqlType.int),
      TableColumn("phone", SqlType.varchar),
      TableColumn("intl_plan", SqlType.char)
    )

    val sampleTable =
      FeaturesTableDescription(sampleDataTableId,
                               List(TableColumn("ID", SqlType.int)),
                               None,
                               validateSchema = false,
                               None,
                               0.67)
    val sampleHeaders = List(
      TableColumn("ID", SqlType.int),
      TableColumn("stress_before_test1", SqlType.numeric),
      TableColumn("score_test1", SqlType.numeric),
      TableColumn("IQ", SqlType.numeric),
      TableColumn("cognitive_task2", SqlType.numeric),
      TableColumn("practice_task2", SqlType.numeric),
      TableColumn("response_time_task2", SqlType.numeric),
      TableColumn("college_math", SqlType.numeric),
      TableColumn("score_math_course1", SqlType.numeric),
      TableColumn("score_math_course2", SqlType.numeric)
    )

    val sampleData = List(
      JsObject("ID"                  -> JsNumber(1),
               "stress_before_test1" -> JsNumber(2.0),
               "score_test1"         -> JsNumber(1.0))
    )

    val cdeTable = FeaturesTableDescription(
      cdeFeaturesATableId,
      List(TableColumn("subjectcode", SqlType.varchar)),
      Some(TableColumn("dataset", SqlType.varchar)),
      validateSchema = false,
      None,
      0.67
    )
    val cdeHeaders = List(
      TableColumn("subjectcode", SqlType.varchar),
      TableColumn("apoe4", SqlType.int),
      TableColumn("lefthippocampus", SqlType.numeric),
      TableColumn("dataset", SqlType.varchar)
    )

    val cdeData = List(cdeRow("p001", 2, 1.37),
                       cdeRow("p002", 2, 1.21),
                       cdeRow("p003", 1, 1.51),
                       cdeRow("p004", 1, 1.45),
                       cdeRow("p005", 0, 1.68),
                       cdeRow("p006", 1, 1.62))

    private def cdeRow(id: String, apoe4: Int, leftHippocampus: Double) =
      JsObject("subjectcode"     -> JsString(id),
               "apoe4"           -> JsNumber(apoe4),
               "lefthippocampus" -> JsNumber(leftHippocampus),
               "dataset"         -> JsString("desd-synthdata"))

  }

  object Jobs extends ValidatedValues {
    import Algorithms._
    import VariablesMetas._

    val dummyPfa: JsObject =
      """
           {
             "input": {},
             "output": {},
             "action": [],
             "cells": {}
           }
        """.stripMargin.parseJson.asJsObject

    val knnDockerJob = DockerJob(
      jobId = "1234",
      query = FeaturesQuery(List("apoe4"),
                            List("lefthippocampus"),
                            Nil,
                            cdeFeaturesATableId,
                            None,
                            None,
                            None),
      algorithmSpec = knnWithK5,
      algorithmDefinition = knnDefinition,
      metadata = featuresAVariablesMeta
        .selectVariables(List(VariableId("apoe4"), VariableId("lefthippocampus")))
        .value
    )

    val knnJobResult = PfaJobResult(knnDockerJob.jobId,
                                    "testNode",
                                    OffsetDateTime.now(),
                                    knnDefinition.code,
                                    dummyPfa)

    val anovaDockerJob = DockerJob(
      jobId = "5433",
      query = FeaturesQuery(List("cognitive_task2"),
                            List("score_test1", "college_math"),
                            Nil,
                            sampleDataTableId,
                            None,
                            None,
                            None),
      algorithmSpec = anovaFactorial,
      algorithmDefinition = anovaDefinition,
      metadata = sampleVariablesMeta
        .selectVariables(
          List(VariableId("cognitive_task2"), VariableId("score_test1"), VariableId("college_math"))
        )
        .value
    )

    val anovaJobResult = JsonDataJobResult(anovaDockerJob.jobId,
                                           "testNode",
                                           OffsetDateTime.now(),
                                           Shapes.tabularDataResource,
                                           anovaDefinition.code,
                                           Some(JsArray()))
  }
}
