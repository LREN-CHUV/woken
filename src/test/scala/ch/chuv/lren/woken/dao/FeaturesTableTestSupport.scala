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

package ch.chuv.lren.woken.dao

import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.messages.variables.SqlType
import spray.json.{ JsNumber, JsObject, JsString }

trait FeaturesTableTestSupport {

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

  val cdeData = List(
    JsObject("subjectcode"     -> JsString("p001"),
             "apoe4"           -> JsNumber(2),
             "lefthippocampus" -> JsNumber(1.37),
             "dataset"         -> JsString("desd-synthdata"))
  )

}
