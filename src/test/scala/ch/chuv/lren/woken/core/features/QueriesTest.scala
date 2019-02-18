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

import ch.chuv.lren.woken.messages.datasets.TableId
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, CodeValue, MiningQuery, UserId }
import org.scalatest.{ Matchers, WordSpec }
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.VariableId

import scala.collection.immutable.TreeSet

class QueriesTest extends WordSpec with Matchers {

  "QueryEnhanced" should {
    import Queries.QueryEnhanced

    val algorithm: AlgorithmSpec = AlgorithmSpec(
      code = "knn",
      parameters = List(CodeValue("k", "5"), CodeValue("n", "1")),
      step = None
    )

    val user: UserId = UserId("test")

    // a < 10
    val rule = SingleFilterRule("a", "a", "number", InputType.number, Operator.less, List("10"))

    val query = MiningQuery(
      user = user,
      variables = List("target").map(VariableId),
      covariables = List("a", "b", "c").map(VariableId),
      covariablesMustExist = false,
      grouping = List("grp1", "grp2").map(VariableId),
      filters = Some(rule),
      targetTable = None,
      datasets = TreeSet(),
      algorithm = algorithm,
      executionPlan = None
    )

    val inputTable = TableId("test_db", "inputTable")

    "generate the SQL query" ignore {
      val featuresQuery = query.features(inputTable, None)
      featuresQuery.sql shouldBe
      """SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "a" < 10"""
    }

    "generate a SQL query filtering null values" ignore {
      val featuresQuery = query
        .filterNulls(variablesCanBeNull = true, covariablesCanBeNull = true)
        .features(inputTable, None)
      featuresQuery.sql shouldBe
      """SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "a" < 10"""

      val featuresQuery2 = query
        .filterNulls(variablesCanBeNull = false, covariablesCanBeNull = true)
        .features(inputTable, None)
      featuresQuery2.sql shouldBe
      """SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "target" IS NOT NULL AND "a" < 10"""

      val featuresQuery3 = query
        .filterNulls(variablesCanBeNull = false, covariablesCanBeNull = false)
        .features(inputTable, None)
      featuresQuery3.sql shouldBe
      """SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "target" IS NOT NULL AND "a" IS NOT NULL
          | AND "b" IS NOT NULL AND "c" IS NOT NULL AND "grp1" IS NOT NULL AND "grp2" IS NOT NULL AND "a" < 10""".stripMargin
        .replace("\n", "")

      val featuresQuery4 = query
        .filterNulls(variablesCanBeNull = true, covariablesCanBeNull = false)
        .features(inputTable, None)
      featuresQuery4.sql shouldBe
      """SELECT "target","a","b","c","grp1","grp2" FROM inputTable WHERE "a" IS NOT NULL
          | AND "b" IS NOT NULL AND "c" IS NOT NULL AND "grp1" IS NOT NULL AND "grp2" IS NOT NULL AND "a" < 10""".stripMargin
        .replace("\n", "")

    }

    "generate valid database field names" ignore {
      val badQuery = MiningQuery(
        user = user,
        variables = List("tarGet-var").map(VariableId),
        covariables = List("1a", "12-b", "c").map(VariableId),
        covariablesMustExist = false,
        grouping = List("grp1", "grp2").map(VariableId),
        filters =
          Some(SingleFilterRule("a", "1a", "number", InputType.number, Operator.less, List("10"))),
        targetTable = None,
        datasets = TreeSet(),
        algorithm = algorithm,
        executionPlan = None
      )

      val featuresQuery = badQuery.features(inputTable, None)
      featuresQuery.sql shouldBe
      """SELECT "target_var","_1a","_12_b","c","grp1","grp2" FROM inputTable WHERE "_1a" < 10"""
    }

    //"include offset information in query" in {
    //  val featuresQuery: FeaturesQuery =
    //    query.features("inputTable", Some(QueryOffset(start = 0, count = 20)))
    //
    //  val expected: String =
    //    """SELECT "target","a","b","c","grp1","grp2", abs(('x'||substr(md5(subjectcode),1,16))::bit(64)::BIGINT) as "_sort_" FROM inputTable WHERE "a" < 10 EXCEPT ALL (SELECT "target","a","b","c","grp1","grp2", abs(('x'||substr(md5(subjectcode),1,16))::bit(64)::BIGINT) as "_sort_" FROM inputTable WHERE "a" < 10 ORDER BY "_sort_" OFFSET 0 LIMIT 20) ORDER BY "_sort_" """.trim
    //
    //  featuresQuery.sql shouldBe expected
    //
    //}

  }
}
