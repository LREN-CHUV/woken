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

package ch.chuv.lren.woken.core.features

import ch.chuv.lren.woken.core.model.TableColumn
import ch.chuv.lren.woken.messages.query.filters.{ InputType, Operator, SingleFilterRule }
import ch.chuv.lren.woken.messages.variables.SqlType
import org.scalatest.{ Matchers, WordSpec }

class FeaturesQueryTest extends WordSpec with Matchers {

  "FeaturesQuery" should {

    "generate a SQL statement when no filter or sampling are used" in {

      val query = FeaturesQuery(
        dbVariables = List("a"),
        dbCovariables = List("b", "c"),
        dbGrouping = Nil,
        dbName = "test_db",
        dbSchema = None,
        dbTable = "table",
        filters = None,
        sampling = None,
        orderBy = None
      )

      query.sql shouldBe """SELECT "a","b","c" FROM "table""""
    }

    "generate a SQL statement when a filter is used, no sampling" in {

      val query = FeaturesQuery(
        dbVariables = List("a"),
        dbCovariables = List("b", "c"),
        dbGrouping = Nil,
        dbName = "test_db",
        dbSchema = None,
        dbTable = "table",
        filters = Some(
          SingleFilterRule("i", "i", "text", InputType.number, Operator.greaterOrEqual, List("5"))
        ),
        sampling = None,
        orderBy = None
      )

      query.sql shouldBe """SELECT "a","b","c" FROM "table" WHERE "i" >= 5"""
    }

    "generate a SQL statement when no filter is used, leaving out a partition and using a string field for ordering" in {

      val query = FeaturesQuery(
        dbVariables = List("a"),
        dbCovariables = List("b", "c"),
        dbGrouping = Nil,
        dbName = "test_db",
        dbSchema = None,
        dbTable = "table",
        filters = None,
        sampling = Some(LeaveOutPartition(10, 2, Some(TableColumn("id", SqlType.varchar)))),
        orderBy = None
      )

      query.sql shouldBe """SELECT "a","b","c",ntile(10) over (order by abs(('x'||substr(md5("id"),1,16))::bit(64)::BIGINT)) as "_window_" FROM "table" WHERE "_window_" != 3"""
    }

    "generate a SQL statement when a filter is used, leaving out a partition and using a string field for ordering" in {

      val query = FeaturesQuery(
        dbVariables = List("a"),
        dbCovariables = List("b", "c"),
        dbGrouping = Nil,
        dbName = "test_db",
        dbSchema = None,
        dbTable = "table",
        filters = Some(
          SingleFilterRule("i", "i", "text", InputType.number, Operator.greaterOrEqual, List("5"))
        ),
        sampling = Some(LeaveOutPartition(10, 2, Some(TableColumn("id", SqlType.varchar)))),
        orderBy = None
      )

      query.sql shouldBe """SELECT "a","b","c",ntile(10) over (order by abs(('x'||substr(md5("id"),1,16))::bit(64)::BIGINT)) as "_window_" FROM "table" WHERE "i" >= 5 AND "_window_" != 3"""
    }

    "generate a SQL statement when no filter is used, leaving out a partition and using an integer field for ordering" in {

      val query = FeaturesQuery(
        dbVariables = List("a"),
        dbCovariables = List("b", "c"),
        dbGrouping = Nil,
        dbName = "test_db",
        dbSchema = None,
        dbTable = "table",
        filters = None,
        sampling = Some(LeaveOutPartition(10, 2, Some(TableColumn("id", SqlType.int)))),
        orderBy = None
      )

      query.sql shouldBe """SELECT "a","b","c",ntile(10) over (order by pseudo_encrypt("id")) as "_window_" FROM "table" WHERE "_window_" != 3"""
    }

    "generate a SQL statement when no filter is used, leaving out a partition and using a generated random for ordering" in {

      val query = FeaturesQuery(
        dbVariables = List("a"),
        dbCovariables = List("b", "c"),
        dbGrouping = Nil,
        dbName = "test_db",
        dbSchema = None,
        dbTable = "table",
        filters = None,
        sampling = Some(LeaveOutPartition(10, 2, None)),
        orderBy = None
      )

      query.sql shouldBe """SELECT "a","b","c",ntile(10) over (order by random()) as "_window_" FROM "table" WHERE "_window_" != 3"""
    }
  }

}
