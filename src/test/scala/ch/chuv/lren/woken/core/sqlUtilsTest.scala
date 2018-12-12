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

package ch.chuv.lren.woken.core

import ch.chuv.lren.woken.core.model.database.TableId
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.service.TestServices.database
import org.scalatest.{ Matchers, WordSpec }

import doobie.implicits._

class sqlUtilsTest extends WordSpec with Matchers {

  "sqlUtils" should {

    "generate an equals where clause between two tables and one column" in {
      val table1 = FeaturesTableDescription(
        TableId(database, None, "cde_features_a"),
        List(TableColumn("subjectcode", SqlType.varchar)),
        Some(TableColumn("dataset", SqlType.varchar)),
        validateSchema = false,
        None,
        0.67
      )
      val headers1 = List(
        TableColumn("subjectcode", SqlType.varchar)
      )

      val table2   = table1.copy(table = TableId(database, None, "cde_features_b"))
      val headers2 = headers1

      sqlUtils.frEqual(table1, headers1, table2, headers2).toString() shouldBe
      fr""""cde_features_a"."subjectcode" = "cde_features_b"."subjectcode""""
        .toString()
    }

    "generate an equals where clause between two tables and two columns" in {
      val table1 = FeaturesTableDescription(
        TableId(database, None, "cde_features_a"),
        List(TableColumn("subjectcode", SqlType.varchar)),
        Some(TableColumn("dataset", SqlType.varchar)),
        validateSchema = false,
        None,
        0.67
      )
      val headers1 = List(
        TableColumn("subjectcode", SqlType.varchar),
        TableColumn("dataset", SqlType.varchar)
      )

      val table2   = table1.copy(table = TableId(database, None, "cde_features_b"))
      val headers2 = headers1

      sqlUtils.frEqual(table1, headers1, table2, headers2).toString() shouldBe
      fr""""cde_features_a"."subjectcode" = "cde_features_b"."subjectcode" AND "cde_features_a"."dataset" = "cde_features_b"."dataset""""
        .toString()
    }
  }

}
