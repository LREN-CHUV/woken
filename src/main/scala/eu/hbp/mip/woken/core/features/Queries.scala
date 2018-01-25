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

package eu.hbp.mip.woken.core.features

import eu.hbp.mip.woken.messages.query.Query
import eu.hbp.mip.woken.messages.query.filters._
import eu.hbp.mip.woken.messages.variables.{ FeatureIdentifier, VariableId }
import org.postgresql.core.Utils

case class QueryOffset(start: Int, count: Int)

object Queries {
  private val numberRegex = "[-+]?\\d+(\\.\\d+)?".r

  implicit class SqlStrings(val s: String) extends AnyVal {

    def safe: String =
      if (numberRegex.pattern.matcher(s).matches())
        s
      else {
        val sb = new java.lang.StringBuilder("'")
        Utils.escapeIdentifier(sb, s)
        sb.append("'")
        sb.toString
      }

    def quoted: String = s""""$s""""
  }

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
          case Operator.beginsWith    => s"${s.field.quoted} LIKE ${(s.value.head + "%").safe}'"
          case Operator.notBeginsWith => s"${s.field.quoted} NOT LIKE ${(s.value.head + "%").safe}'"
          case Operator.contains      => s"${s.field.quoted} LIKE ${("%" + s.value.head + "%").safe}'"
          case Operator.notContains =>
            s"${s.field.quoted} NOT LIKE ${("%" + s.value.head + "%").safe}'"
          case Operator.endsWith    => s"${s.field.quoted} LIKE ${("%" + s.value.head).safe}'"
          case Operator.notEndsWith => s"${s.field.quoted} NOT LIKE ${("%" + s.value.head).safe}'"
          case Operator.isEmpty     => s"'COALESCE(${s.field.quoted}, '') = ''"
          case Operator.isNotEmpty  => s"'COALESCE(${s.field.quoted}, '') != ''"
          case Operator.isNull      => s"${s.field.quoted} IS NULL"
          case Operator.isNotNull   => s"${s.field.quoted} IS NOT NULL"
        }
    }
  }

  // TODO: add support for GroupId as feature
  implicit class QueryEnhanced(val query: Query) extends AnyVal {

    /** Convert variable to lowercase as Postgres returns lowercase fields in its result set
      * Variables codes are sanitized to ensure valid database field names using the following conversions:
      * + replace - by _
      * + prepend _ to the variable name if it starts by a number
      */
    private[this] def toField(feature: FeatureIdentifier) = feature match {
      case v: VariableId => v.code.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1")
      case _             => throw new NotImplementedError("Need to add support for groups as a feature")
    }

    def dbAllVars: List[String] = (dbVariables ++ dbCovariables ++ dbGrouping).distinct

    def dbVariables: List[String]   = query.variables.map(toField)
    def dbCovariables: List[String] = query.covariables.map(toField)
    def dbGrouping: List[String]    = query.grouping.map(toField)

    def features(inputTable: String,
                 excludeNullValues: Boolean,
                 shadowOffset: Option[QueryOffset]): FeaturesQuery = {

      val nonNullableFields = if (excludeNullValues) query.dbAllVars else query.dbVariables
      val notNullFilters: List[FilterRule] = nonNullableFields
        .map(v => SingleFilterRule(v, v, "string", InputType.text, Operator.isNotNull, Nil))
      val mergingQueryFilters = query.filters.fold(notNullFilters)(f => notNullFilters :+ f)
      val filters: FilterRule = mergingQueryFilters match {
        case List(f) => f
        case _       => CompoundFilterRule(Condition.and, mergingQueryFilters)
      }

      val selectNoPaging =
        s"select ${query.dbAllVars.map(_.quoted).mkString(",")} FROM $inputTable WHERE ${filters.toSqlWhere}"

      val sqlQuery = shadowOffset.fold(selectNoPaging) { o =>
        selectNoPaging + s" EXCEPT ALL (" + selectNoPaging + s" OFFSET ${o.start} LIMIT ${o.count})"
      }

      FeaturesQuery(dbVariables, dbCovariables, dbGrouping, inputTable, sqlQuery)
    }
  }

}
