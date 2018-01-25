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
import eu.hbp.mip.woken.messages.query.filters._

// TODO: merge with Queries?

case class QueryOffset(start: Int, count: Int)

object FeaturesHelper {

  implicit class FilterRuleToSql(val rule: FilterRule) extends AnyVal {
    def toSqlWhere: String = rule match {
      case c: CompoundFilterRule =>
        c.rules.map(r => r.toSqlWhere).mkString(s" ${c.condition.toString} ")
      case s: SingleFilterRule =>
        s.operator match {
          case Operator.equal          => s"${s.field.quoted} = ${s.value.head.safe}"
          case Operator.notEqual       => s"${s.field.quoted} != ${s.value.head.safe}"
          case Operator.less           => s"${s.field.quoted} < ${s.value.head.safe}"
          case Operator.greater        => s"${s.field.quoted} > ${s.value.head.safe}"
          case Operator.lessOrEqual    => s"${s.field.quoted} <= ${s.value.head.safe}"
          case Operator.greaterOrEqual => s"${s.field.quoted} >= ${s.value.head.safe}"
          case Operator.in             => s"${s.field.quoted} IN (${s.value.map(_.safe).mkString(",")})"
          case Operator.notIn          => s"${s.field.quoted} NOT IN (${s.value.map(_.safe).mkString(",")})"
          case Operator.between =>
            s"${s.field.quoted} BETWEEN ${s.value.head.safe} AND ${s.value.last.safe}"
          case Operator.notBetween =>
            s"${s.field.quoted} NOT BETWEEN ${s.value.head.safe} AND ${s.value.last.safe}"
          case Operator.beginsWith    => s"${s.field.quoted} LIKE ${(s.value.head + '%').safe}'"
          case Operator.notBeginsWith => s"${s.field.quoted} NOT LIKE ${(s.value.head + '%').safe}'"
          case Operator.contains      => s"${s.field.quoted} LIKE ${('%' + s.value.head + '%').safe}'"
          case Operator.notContains =>
            s"${s.field.quoted} NOT LIKE ${('%' + s.value.head + '%').safe}'"
          case Operator.endsWith    => s"${s.field.quoted} LIKE ${('%' + s.value.head).safe}'"
          case Operator.notEndsWith => s"${s.field.quoted} NOT LIKE ${('%' + s.value.head).safe}'"
          case Operator.isEmpty     => s"'COALESCE(${s.field.quoted}, '') = ''"
          case Operator.isNotEmpty  => s"'COALESCE(${s.field.quoted}, '') != ''"
          case Operator.isNull      => s"${s.field.quoted} IS NULL"
          case Operator.isNotNull   => s"${s.field.quoted} IS NOT NULL"
        }
    }
  }

  def buildQueryFeaturesSql(inputTable: String,
                            query: MiningQuery,
                            excludeNullValues: Boolean,
                            shadowOffset: Option[QueryOffset]): String = {

    val filters: Option[FilterRule] = if (excludeNullValues) {
      val notNullFilters: List[FilterRule] = query.dbAllVars
        .map(v => SingleFilterRule(v, v, "string", InputType.text, Operator.isNotNull, Nil))
      val mergingQueryFilters = query.filters.fold(notNullFilters)(f => notNullFilters :+ f)
      mergingQueryFilters match {
        case List(f) => Some(f)
        case _       => Some(CompoundFilterRule(Condition.and, mergingQueryFilters))
      }
    } else {
      query.filters
    }

    val whereClause = filters.fold("")(f => s" WHERE ${f.toSqlWhere}")
    val selectNoPaging =
      s"select ${query.dbAllVars.map(_.quoted).mkString(",")} from $inputTable $whereClause"

    shadowOffset.fold(selectNoPaging) { o =>
      selectNoPaging + s" EXCEPT ALL (" + selectNoPaging + s" OFFSET ${o.start} LIMIT ${o.count})"
    }
  }

}
