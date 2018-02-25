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

import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, CodeValue, MiningQuery, UserId }
import org.scalatest.{ Matchers, WordSpec }
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.VariableId

class QueriesTest extends WordSpec with Matchers {

  "SqlStrings" should {
    import Queries.SqlStrings

    "produce safe numerical output" in {
      "1".safeValue shouldBe "1"
      "-2".safeValue shouldBe "-2"
      "+3".safeValue shouldBe "3"
      "1.23".safeValue shouldBe "1.23"
      "-1.23".safeValue shouldBe "-1.23"
    }

    "wrap strings into quotes" in {
      "a".safeValue shouldBe "'a'"
      "3e".safeValue shouldBe "'3e'"
      "a'b'c".safeValue shouldBe "'a''b''c'"
    }

    "prevent SQL injection from values [security]" in {
      "Bobby'; DROP DATABASE; --".safeValue shouldBe "'Bobby''; DROP DATABASE; --'"
      "10; DROP DATABASE; --".safeValue shouldBe "'10; DROP DATABASE; --'"
      "' + (SELECT TOP 1 password FROM users ) + '".safeValue shouldBe "''' + (SELECT TOP 1 password FROM users ) + '''"
    }

    "quote identifiers" in {
      "a".identifier shouldBe """"a""""
      "3".identifier shouldBe """"3""""
      "a c".identifier shouldBe """"a c""""
    }

    "prevent SQL injection from identifiers [security]" in {
      """Bob"; DROP DATABASE --""".identifier shouldBe """"Bob""; DROP DATABASE --""""
    }
  }

  "FilterRuleToSql" should {
    import Queries.FilterRuleToSql

    "generate the where clause for a simple filter" in {
      val simpleFilter = SingleFilterRule("col1",
                                          "col1",
                                          "string",
                                          InputType.number,
                                          Operator.greaterOrEqual,
                                          List("10.5"))
      simpleFilter.toSqlWhere shouldBe """"col1" >= 10.5"""
    }

    "generate the where clause for a more complex filter" in {
      val left = SingleFilterRule("col1",
                                  "col1",
                                  "string",
                                  InputType.number,
                                  Operator.greaterOrEqual,
                                  List("10.5"))

      val right = SingleFilterRule("col2",
                                   "col2",
                                   "string",
                                   InputType.text,
                                   Operator.beginsWith,
                                   List("beginning"))

      val compoundFilter = CompoundFilterRule(Condition.and, List(left, right))
      compoundFilter.toSqlWhere shouldBe """"col1" >= 10.5 AND "col2" LIKE 'beginning%'""".stripMargin
    }

    // try to find some tricky filters, filters with bad values that may be injected by an attacker
  }

  "QueryEnhanced" should {
    import Queries.QueryEnhanced

    val algorithm: AlgorithmSpec = AlgorithmSpec(
      code = "knn",
      parameters = List(CodeValue("k", "5"), CodeValue("n", "1"))
    )

    val user: UserId = UserId("test")

    // a < 10
    val rule = SingleFilterRule("a", "a", "number", InputType.number, Operator.less, List("10"))

    val query = MiningQuery(
      user = user,
      variables = List("target").map(VariableId),
      covariables = List("a", "b", "c").map(VariableId),
      grouping = List("grp1", "grp2").map(VariableId),
      filters = Some(rule),
      targetTable = None,
      datasets = Set(),
      algorithm = algorithm,
      executionPlan = None
    )

    "generate the SQL query" in {
      val featuresQuery = query.features("inputTable", excludeNullValues = false, None)
      featuresQuery.query shouldBe
      """SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "target" IS NOT NULL AND "a" < 10"""
    }

    "generate valid database field names" in {
      val badQuery = MiningQuery(
        user = user,
        variables = List("tarGet-var").map(VariableId),
        covariables = List("1a", "12-b", "c").map(VariableId),
        grouping = List("grp1", "grp2").map(VariableId),
        filters =
          Some(SingleFilterRule("a", "1a", "number", InputType.number, Operator.less, List("10"))),
        targetTable = None,
        datasets = Set(),
        algorithm = algorithm,
        executionPlan = None
      )

      val featuresQuery = badQuery.features("inputTable", excludeNullValues = false, None)
      featuresQuery.query shouldBe
      """SELECT "target_var","_1a","_12_b","c","grp1","grp2" FROM inputTable WHERE "target_var" IS NOT NULL AND "_1a" < 10"""
    }

    "include offset information in query" in {
      val featuresQuery = query.features("inputTable",
                                         excludeNullValues = false,
                                         Some(QueryOffset(start = 0, count = 20)))

      featuresQuery.query shouldBe
      """SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "target" IS NOT NULL AND "a" < 10
        |EXCEPT ALL (SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "target" IS NOT NULL AND "a" < 10 OFFSET 0 LIMIT 20)""".stripMargin
        .replace("\n", " ")
    }

  }
}
