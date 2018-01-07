/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.test

import eu.hbp.mip.woken.messages.external._
import spray.json.{
  JsFalse,
  JsNull,
  JsNumber,
  JsString,
  JsTrue,
  JsValue,
  SortedPrinter
}
import spray.json._
import scala.io.Source

trait Queries {

  def experimentQuery(algorithm: String, parameters: List[CodeValue]) =
    ExperimentQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_test1"), VariableId("college_math")),
      Nil,
      "",
      List(AlgorithmSpec(algorithm, parameters)),
      List(ValidationSpec("kfold", List(CodeValue("k", "2"))))
    )

  def loadJson(path: String): JsValue = {
    val source = Source.fromURL(getClass.getResource(path))
    source.mkString.parseJson
  }

  def approximate(json: JsValue): String = {
    val sb = new java.lang.StringBuilder()
    new ApproximatePrinter().print(json, sb)
    sb.toString
  }

  class ApproximatePrinter extends SortedPrinter {

    override protected def printObject(members: Map[String, JsValue],
                                       sb: java.lang.StringBuilder,
                                       indent: Int): Unit = {
      val filteredMembers = members.map {
        case ("jobId", _)     => "jobId" -> JsString("*")
        case ("timestamp", _) => "timestamp" -> JsNumber(0.0)
        case (k, v)           => k -> v
      }
      super.printObject(filteredMembers, sb, indent)
    }

    override protected def printLeaf(j: JsValue,
                                     sb: java.lang.StringBuilder): Unit =
      j match {
        case JsNull      => sb.append("null")
        case JsTrue      => sb.append("true")
        case JsFalse     => sb.append("false")
        case JsNumber(x) => sb.append(f"$x%1.5f")
        case JsString(x) => printString(x, sb)
        case _           => throw new IllegalStateException
      }

  }

}
