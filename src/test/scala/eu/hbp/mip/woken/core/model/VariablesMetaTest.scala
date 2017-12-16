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

import cats.data.NonEmptyList
import cats.data.Validated.Invalid
import eu.hbp.mip.woken.util.JsonUtils
import org.scalatest.{ Matchers, WordSpec }
import spray.json._

class VariablesMetaTest extends WordSpec with Matchers with JsonUtils {

  "VariablesMeta" should {
    "locate metadata for a sequence of variables" in {

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

      selectedMeta.toOption.get shouldBe expected
    }

    "locate metadata for another sequence of variables" in {

      val json = loadJson("/metadata/mip_cde_variables.json")
      val meta =
        VariablesMeta(1,
                      "test",
                      json.asJsObject,
                      "mip_cde_features",
                      "dataset,gender,agegroup,alzheimerbroadcategory")

      val selectedMeta = meta.selectVariablesMeta(
        List("rs610932_a",
             "cerebellarvermallobulesviiix",
             "leftmorgmedialorbitalgyrus",
             "agegroup",
             "alzheimerbroadcategory")
      )

      val expected =
        """
        |{
        |  "leftmorgmedialorbitalgyrus":{"description":"","methodology":"lren-nmm-volumes","label":"Left medial orbital gyrus","code":"leftmorgmedialorbitalgyrus","units":"cm3","type":"real"},
        |  "cerebellarvermallobulesviiix":{"description":"","methodology":"lren-nmm-volumes","label":"Cerebellar Vermal Lobules VIII-X","code":"cerebellarvermallobulesviiix","units":"cm3","type":"real"},
        |  "agegroup":{"enumerations":[{"code":"-50y","label":"-50y"},{"code":"50-59y","label":"50-59y"},{"code":"60-69y","label":"60-69y"},{"code":"70-79y","label":"70-79y"},{"code":"+80y","label":"+80y"}],"description":"Age Group","methodology":"mip-cde","label":"Age Group","code":"agegroup","type":"polynominal"},
        |  "alzheimerbroadcategory":{"enumerations":[{"code":"AD","label":"Alzheimer''s disease"},{"code":"CN","label":"Cognitively Normal"},{"code":"Other","label":"Other"}],"description":"There will be two broad categories taken into account. Alzheimer''s disease (AD) in which the diagnostic is 100% certain and \"Other\" comprising the rest of Alzheimer''s related categories. The \"Other\" category refers to Alzheime''s related diagnosis which origin can be traced to other pathology eg. vascular. In this category MCI diagnosis can also be found. In summary, all Alzheimer''s related diagnosis that are not pure.","methodology":"mip-cde","label":"Alzheimer Broad Category","code":"alzheimerbroadcategory","type":"polynominal"},
        |  "rs610932_a":{"sql_type":"int","enumerations":[{"code":0,"label":0},{"code":1,"label":1},{"code":2,"label":2}],"description":"","methodology":"lren-nmm-volumes","label":"rs610932_A","code":"rs610932_a","type":"polynominal"}
        |}
      """.stripMargin.parseJson

      selectedMeta.toOption.get shouldBe expected
    }

    "return an error for unknown variables" in {

      val json = loadJson("/metadata/sample_variables.json")
      val meta =
        VariablesMeta(1, "test", json.asJsObject, "sample_data", "state,custserv_calls,churn")

      val selectedMeta =
        meta.selectVariablesMeta(List("IQ", "score_math_course1", "not_me", "look_a_ghost"))

      val expected = Invalid(NonEmptyList("Cannot not find metadata for not_me, look_a_ghost", Nil))

      selectedMeta shouldBe expected
    }

  }

}
