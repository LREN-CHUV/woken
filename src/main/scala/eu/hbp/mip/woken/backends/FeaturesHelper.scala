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

package eu.hbp.mip.woken.backends

import eu.hbp.mip.woken.messages.query.MiningQuery
import eu.hbp.mip.woken.core.model.Queries._
import eu.hbp.mip.woken.messages.query.filters.{
  CompoundFilterRule,
  FilterRule,
  Operator,
  SingleFilterRule
}

// TODO: merge with Queries?

case class QueryOffset(start: Int, count: Int)

object FeaturesHelper {

  implicit class FilterRuleToSql(val rule: FilterRule) extends AnyVal {
    def toSqlWhere: String = rule match {
      case c: CompoundFilterRule =>
      case s: SingleFilterRule =>
        s.operator match {
          case Operator.equal          => s"'${s.field}' = XXX"
          case Operator.notEqual       => s"'${s.field}' != XXX"
          case Operator.less           => s"'${s.field}' < ${s.value.head}"
          case Operator.greater        => s"'${s.field}' > ${s.value.head}"
          case Operator.lessOrEqual    => s"'${s.field}' <= ${s.value.head}"
          case Operator.greaterOrEqual => s"'${s.field}' >= ${s.value.head}"
          case Operator.in             => s"'${s.field}' IN (XXX)"
          case Operator.notIn          => s"'${s.field}' NOT IN (XXX)"
          case Operator.between        => s"'${s.field}' BETWEEN ${s.value.head} AND ${s.value.tail}"
          case Operator.notBetween =>
            s"'${s.field}' NOT BETWEEN ${s.value.head} AND ${s.value.tail}"
          case Operator.beginsWith    => s"'${s.field}' LIKE '${s.value.head}%'"
          case Operator.notBeginsWith => s"'${s.field}' NOT LIKE '${s.value.head}%'"
          case Operator.contains      => s"'${s.field}' LIKE '%${s.value.head}%'"
          case Operator.notContains   => s"'${s.field}' NOT LIKE '%${s.value.head}%'"
          case Operator.endsWith      => s"'${s.field}' LIKE '%${s.value.head}'"
          case Operator.notEndsWith   => s"'${s.field}' NOT LIKE '%${s.value.head}'"
          case Operator.isEmpty       => s"'COALESCE(${s.field}, '') = ''"
          case Operator.isNotEmpty    => s"'COALESCE(${s.field}, '') != ''"
          case Operator.isNull        => s"'${s.field}' IS NULL"
          case Operator.isNotNull     => s"'${s.field}' IS NOT NULL"
        }

    }
  }

  def buildQueryFeaturesSql(inputTable: String,
                            query: MiningQuery,
                            shadowOffset: Option[QueryOffset]): String = {

    val varListDbSafe = query.dbAllVars

    // TODO: some algorithms can work with null / missing data
    val sql = s"select ${varListDbSafe.mkString(",")} from $inputTable where ${varListDbSafe
      .map(_ + " is not null")
      .mkString(" and ")} ${if (query.filters != "") s"and ${query.filters}" else ""}"

    shadowOffset.fold(sql) { o =>
      sql + s" EXCEPT ALL (" + sql + s" OFFSET ${o.start} LIMIT ${o.count})"
    }
  }

}
