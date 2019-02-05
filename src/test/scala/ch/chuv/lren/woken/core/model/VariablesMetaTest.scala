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

package ch.chuv.lren.woken.core.model

import cats.data.NonEmptyList
import cats.data.Validated.Invalid
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.messages.variables._
import org.scalatest.{ Matchers, WordSpec }
import spray.json._
import variablesProtocol._

class VariablesMetaTest extends WordSpec with Matchers with JsonUtils {

  "VariablesMeta" should {
    "locate metadata for a sequence of variables" in {

      val json = loadJson("/metadata/sample_variables.json")
      val meta =
        VariablesMeta(1,
                      "test",
                      json.convertTo[GroupMetaData],
                      "sample_data",
                      List("state", "custserv_calls", "churn").map(VariableId))

      val selectedMeta =
        meta.filterVariables(List("IQ", "score_math_course1").map(VariableId).contains)

      val expected = List(
        VariableMetaData(
          code = "IQ",
          label = "IQ",
          `type` = VariableType.real,
          methodology = Some("test"),
          sqlType = None,
          description = Some(""),
          units = None,
          enumerations = None,
          length = None,
          minValue = None,
          maxValue = None,
          datasets = Set(),
          summaryStatistics = None
        ),
        VariableMetaData(
          code = "score_math_course1",
          label = "Score Math Course 1",
          `type` = VariableType.real,
          methodology = Some("test"),
          sqlType = None,
          description = Some(""),
          units = None,
          enumerations = None,
          length = None,
          minValue = None,
          maxValue = None,
          datasets = Set(),
          summaryStatistics = None
        )
      )

      selectedMeta shouldBe expected

    }

    "locate metadata for another sequence of variables" in {

      val json = loadJson("/metadata/mip_cde_variables.json")
      val meta =
        VariablesMeta(
          1,
          "test",
          json.convertTo[GroupMetaData],
          "mip_cde_features",
          List("dataset", "gender", "agegroup", "alzheimerbroadcategory").map(VariableId)
        )

      val selectedMeta = meta.selectVariables(
        List("rs610932_a",
             "cerebellarvermallobulesviiix",
             "leftmorgmedialorbitalgyrus",
             "agegroup",
             "alzheimerbroadcategory",
             "subjectageyears").map(VariableId)
      )

      val expected =
        """
        |[
        |  {"sql_type":"int","enumerations":[{"code":0,"label":0},{"code":1,"label":1},{"code":2,"label":2}],"description":"","methodology":"lren-nmm-volumes","label":"rs610932_A","code":"rs610932_a","type":"polynominal"},
        |  {"description":"","methodology":"lren-nmm-volumes","label":"Cerebellar Vermal Lobules VIII-X","code":"cerebellarvermallobulesviiix","units":"cm3","type":"real"},
        |  {"description":"","methodology":"lren-nmm-volumes","label":"Left medial orbital gyrus","code":"leftmorgmedialorbitalgyrus","units":"cm3","type":"real"},
        |  {"enumerations":[{"code":"-50y","label":"-50y"},{"code":"50-59y","label":"50-59y"},{"code":"60-69y","label":"60-69y"},{"code":"70-79y","label":"70-79y"},{"code":"+80y","label":"+80y"}],"description":"Age Group","methodology":"mip-cde","label":"Age Group","code":"agegroup","type":"polynominal"},
        |  {"enumerations":[{"code":"AD","label":"Alzheimer''s disease"},{"code":"CN","label":"Cognitively Normal"},{"code":"Other","label":"Other"}],"description":"There will be two broad categories taken into account. Alzheimer''s disease (AD) in which the diagnostic is 100% certain and \"Other\" comprising the rest of Alzheimer''s related categories. The \"Other\" category refers to Alzheime''s related diagnosis which origin can be traced to other pathology eg. vascular. In this category MCI diagnosis can also be found. In summary, all Alzheimer''s related diagnosis that are not pure.","methodology":"mip-cde","label":"Alzheimer Broad Category","code":"alzheimerbroadcategory","type":"polynominal"},
        |  {"description":"Subject age in years.","methodology":"mip-cde","label":"Age Years","minValue":0,"code":"subjectageyears","units":"years","length":3,"maxValue":130,"type":"integer"}
        |]
      """.stripMargin.parseJson.convertTo[List[VariableMetaData]]

      selectedMeta.toOption.get shouldBe expected
    }

    //List(VariableMetaData(rs610932_a,rs610932_A,polynominal,Some(int),Some(lren-nmm-volumes),Some(),None,Some(List(EnumeratedValue(0,0), EnumeratedValue(1,1), EnumeratedValue(2,2))),None,None,None,None,Set()), VariableMetaData(cerebellarvermallobulesviiix,Cerebellar Vermal Lobules VIII-X,real,None,Some(lren-nmm-volumes),Some(),Some(cm3),None,None,None,None,None,Set()), VariableMetaData(leftmorgmedialorbitalgyrus,Left medial orbital gyrus,real,None,Some(lren-nmm-volumes),Some(),Some(cm3),None,None,None,None,None,Set()), VariableMetaData(agegroup,Age Group,polynominal,None,Some(mip-cde),Some(Age Group),None,Some(List(EnumeratedValue(-50y,-50y), EnumeratedValue(50-59y,50-59y), EnumeratedValue(60-69y,60-69y), EnumeratedValue(70-79y,70-79y), EnumeratedValue(+80y,+80y))),None,None,None,None,Set()), VariableMetaData(alzheimerbroadcategory,Alzheimer Broad Category,polynominal,None,Some(mip-cde),Some(There will be two broad categories taken into account. Alzheimer''s disease (AD) in which the diagnostic is 100% certain and "Other" comprising the rest of Alzheimer''s related categories. The "Other" category refers to Alzheime''s related diagnosis which origin can be traced to other pathology eg. vascular. In this category MCI diagnosis can also be found. In summary, all Alzheimer''s related diagnosis that are not pure.),None,Some(List(EnumeratedValue(AD,Alzheimer''s disease), EnumeratedValue(CN,Cognitively Normal), EnumeratedValue(Other,Other))),None,None,None,None,Set()), VariableMetaData(subjectageyears,Age Years,integer,None,Some(mip-cde),Some(Subject age in years.),Some(years),None,Some(3),Some(0.0),Some(130.0),None,Set())) was not equal to
    //List(VariableMetaData(rs610932_a,rs610932_A,polynominal,Some(int),Some(lren-nmm-volumes),Some(),None,Some(List(EnumeratedValue(0,0), EnumeratedValue(1,1), EnumeratedValue(2,2))),None,None,None,None,Set()), VariableMetaData(cerebellarvermallobulesviiix,Cerebellar Vermal Lobules VIII-X,real,None,Some(lren-nmm-volumes),Some(),Some(cm3),None,None,None,None,None,Set()), VariableMetaData(leftmorgmedialorbitalgyrus,Left medial orbital gyrus,real,None,Some(lren-nmm-volumes),Some(),Some(cm3),None,None,None,None,None,Set()), VariableMetaData(subjectageyears,Age Years,integer,None,Some(mip-cde),Some(Subject age in years.),Some(years),None,Some(3),Some(0.0),Some(130.0),None,Set()), VariableMetaData(agegroup,Age Group,polynominal,None,Some(mip-cde),Some(Age Group),None,Some(List(EnumeratedValue(-50y,-50y), EnumeratedValue(50-59y,50-59y), EnumeratedValue(60-69y,60-69y), EnumeratedValue(70-79y,70-79y), EnumeratedValue(+80y,+80y))),None,None,None,None,Set()), VariableMetaData(alzheimerbroadcategory,Alzheimer Broad Category,polynominal,None,Some(mip-cde),Some(There will be two broad categories taken into account. Alzheimer''s disease (AD) in which the diagnostic is 100% certain and "Other" comprising the rest of Alzheimer''s related categories. The "Other" category refers to Alzheime''s related diagnosis which origin can be traced to other pathology eg. vascular. In this category MCI diagnosis can also be found. In summary, all Alzheimer''s related diagnosis that are not pure.),None,Some(List(EnumeratedValue(AD,Alzheimer''s disease), EnumeratedValue(CN,Cognitively Normal), EnumeratedValue(Other,Other))),None,None,None,None,Set()))
    "return an error for unknown variables" in {

      val json = loadJson("/metadata/sample_variables.json")
      val meta =
        VariablesMeta(1,
                      "test",
                      json.convertTo[GroupMetaData],
                      "sample_data",
                      List("state", "custserv_calls", "churn").map(VariableId))

      val selectedMeta =
        meta.selectVariables(
          List("IQ", "score_math_course1", "not_me", "look_a_ghost").map(VariableId)
        )

      val expected =
        Invalid(NonEmptyList("Found 2 out of 4 variables. Missing not_me,look_a_ghost", Nil))

      selectedMeta shouldBe expected
    }

  }
}
