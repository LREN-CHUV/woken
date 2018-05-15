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
import spray.json._

import scala.io.Source

trait Queries {

  def experimentQuery(
      algorithm: String,
      parameters: List[CodeValue],
      variables: List[VariableId] = List(VariableId("cognitive_task2")),
      covariables: List[VariableId] =
        List(VariableId("score_test1"), VariableId("college_math")),
      targetTable: Option[String] = Some("sample_data")): Query =
    multipleExperimentQuery(algorithms =
                              List(AlgorithmSpec(algorithm, parameters)),
                            variables = variables,
                            covariables = covariables,
                            targetTable = targetTable)

  def multipleExperimentQuery(
      algorithms: List[AlgorithmSpec],
      variables: List[VariableId] = List(VariableId("cognitive_task2")),
      covariables: List[VariableId] =
        List(VariableId("score_test1"), VariableId("college_math")),
      targetTable: Option[String] = Some("sample_data")): Query =
    ExperimentQuery(
      user = UserId("test1"),
      variables = variables,
      covariables = covariables,
      grouping = Nil,
      filters = None,
      targetTable = targetTable,
      algorithms = algorithms,
      validations = List(ValidationSpec("kfold", List(CodeValue("k", "2")))),
      trainingDatasets = Set(),
      testingDatasets = Set(),
      validationDatasets = Set(),
      executionPlan = None
    )

  def loadJson(path: String): JsValue = {
    val source = Source.fromURL(getClass.getResource(path))
    source.mkString.parseJson
  }

  def approximate(json: JsValue,
                  skippedTags: List[String] = List("estimator")): String = {
    val sb = new java.lang.StringBuilder()
    new ApproximatePrinter(skippedTags).print(json, sb)
    sb.toString
  }

  class ApproximatePrinter(val skippedTags: List[String])
      extends SortedPrinter {

    override protected def printObject(members: Map[String, JsValue],
                                       sb: java.lang.StringBuilder,
                                       indent: Int): Unit = {
      val filteredMembers = members
        .map {
          case ("jobId", _)     => "jobId" -> JsString("*")
          case ("timestamp", _) => "timestamp" -> JsNumber(0.0)
          case (k, v)           => k -> v
        }
        .filter {
          case ("@", comment) if comment.toString.startsWith("\"PrettyPFA") =>
            false
          case (tag, comment) if skippedTags.contains(tag) =>
            false
          case _ => true
        }
      super.printObject(filteredMembers, sb, indent)
    }

    override protected def printLeaf(j: JsValue,
                                     sb: java.lang.StringBuilder): Unit =
      j match {
        case JsNull  => sb.append("null")
        case JsTrue  => sb.append("true")
        case JsFalse => sb.append("false")
        case JsNumber(x) => {
          val approx = f"$x%1.5f"
          if (approx == "-0.00000")
            sb.append("0.00000")
          else
            sb.append(approx)
        }
        case JsString(x) => printString(x, sb)
        case _           => throw new IllegalStateException
      }

  }

}
