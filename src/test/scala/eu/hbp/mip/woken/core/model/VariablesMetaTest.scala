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

package eu.hbp.mip.woken.core.model

import eu.hbp.mip.woken.util.JsonUtils
import org.scalatest.{ FlatSpec, Matchers }
import spray.json._

class VariablesMetaTest extends FlatSpec with Matchers with JsonUtils {

  "VariablesMeta" should "locate metadata for a sequence of variables" in {

    val json = loadJson("/metadata/sample_variables.json")
    val meta =
      VariablesMeta(1, "test", json.asJsObject, "sample_data", "state,custserv_calls,churn")

    val selectedMeta = meta.selectVariablesMeta(List("IQ", "score_math_course1"))

    val expected =
      """
        |{
        |  "IQ":{"description":"","methodology":"test","label":"IQ","code":"IQ","type":"real"},
        |  "score_math_course1":{"description":"","methodology":"test","label":"Score Math Course 1","code":"score_math_course1","type":"real"}
        |}
      """.stripMargin.parseJson

    selectedMeta shouldBe expected
  }

}
