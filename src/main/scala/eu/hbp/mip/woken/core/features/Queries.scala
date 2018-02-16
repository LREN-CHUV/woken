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

import ch.chuv.lren.woken.messages.query.Query
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.{ FeatureIdentifier, VariableId }
import org.postgresql.core.Utils

case class QueryOffset(start: Int, count: Int)

object Queries {
  private val numberRegex = "[-+]?\\d+(\\.\\d+)?".r

  implicit class SqlStrings(val s: String) extends AnyVal {

    def safeValue: String =
      if (numberRegex.pattern.matcher(s).matches())
        if (s.startsWith("+"))
          s.substring(1)
        else s
      else {
        val sb = new java.lang.StringBuilder("'")
        Utils.escapeLiteral(sb, s, false)
        sb.append("'")
        sb.toString
      }

    def identifier: String = {
      val sb = new java.lang.StringBuilder()
      Utils.escapeIdentifier(sb, s)
      sb.toString
    }
  }

  implicit class FilterRuleToSql(val rule: FilterRule) extends AnyVal {

    def withAdaptedFieldName: FilterRule = rule match {
      case c: CompoundFilterRule =>
        CompoundFilterRule(c.condition, c.rules.map(_.withAdaptedFieldName))
      case s: SingleFilterRule =>
        s.copy(field = s.field.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1"))
    }

    def toSqlWhere: String = rule match {
      case c: CompoundFilterRule =>
        c.rules
          .map {
            case r: SingleFilterRule    => r.toSqlWhere
            case sc: CompoundFilterRule => s"(${sc.toSqlWhere})"
          }
          .mkString(s" ${c.condition.toString} ")
      case s: SingleFilterRule =>
        s.operator match {
          case Operator.equal          => s"${s.field.identifier} = ${s.value.head.safeValue}"
          case Operator.notEqual       => s"${s.field.identifier} != ${s.value.head.safeValue}"
          case Operator.less           => s"${s.field.identifier} < ${s.value.head.safeValue}"
          case Operator.greater        => s"${s.field.identifier} > ${s.value.head.safeValue}"
          case Operator.lessOrEqual    => s"${s.field.identifier} <= ${s.value.head.safeValue}"
          case Operator.greaterOrEqual => s"${s.field.identifier} >= ${s.value.head.safeValue}"
          case Operator.in =>
            s"${s.field.identifier} IN (${s.value.map(_.safeValue).mkString(",")})"
          case Operator.notIn =>
            s"${s.field.identifier} NOT IN (${s.value.map(_.safeValue).mkString(",")})"
          case Operator.between =>
            s"${s.field.identifier} BETWEEN ${s.value.head.safeValue} AND ${s.value.last.safeValue}"
          case Operator.notBetween =>
            s"${s.field.identifier} NOT BETWEEN ${s.value.head.safeValue} AND ${s.value.last.safeValue}"
          case Operator.beginsWith =>
            s"${s.field.identifier} LIKE ${(s.value.head + "%").safeValue}"
          case Operator.notBeginsWith =>
            s"${s.field.identifier} NOT LIKE ${(s.value.head + "%").safeValue}"
          case Operator.contains =>
            s"${s.field.identifier} LIKE ${("%" + s.value.head + "%").safeValue}"
          case Operator.notContains =>
            s"${s.field.identifier} NOT LIKE ${("%" + s.value.head + "%").safeValue}"
          case Operator.endsWith => s"${s.field.identifier} LIKE ${("%" + s.value.head).safeValue}"
          case Operator.notEndsWith =>
            s"${s.field.identifier} NOT LIKE ${("%" + s.value.head).safeValue}"
          case Operator.isEmpty    => s"'COALESCE(${s.field.identifier}, '') = ''"
          case Operator.isNotEmpty => s"'COALESCE(${s.field.identifier}, '') != ''"
          case Operator.isNull     => s"${s.field.identifier} IS NULL"
          case Operator.isNotNull  => s"${s.field.identifier} IS NOT NULL"
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

    def features(defaultInputTable: String,
                 excludeNullValues: Boolean,
                 shadowOffset: Option[QueryOffset]): FeaturesQuery = {

      val inputTable        = query.targetTable.getOrElse(defaultInputTable)
      val nonNullableFields = if (excludeNullValues) query.dbAllVars else query.dbVariables
      val notNullFilters: List[FilterRule] = nonNullableFields
        .map(v => SingleFilterRule(v, v, "string", InputType.text, Operator.isNotNull, Nil))
      val mergingQueryFilters =
        query.filters.map(_.withAdaptedFieldName).fold(notNullFilters)(f => notNullFilters :+ f)
      val filters: FilterRule = mergingQueryFilters match {
        case List(f) => f
        case _       => CompoundFilterRule(Condition.and, mergingQueryFilters)
      }

      val selectNoPaging =
        s"SELECT ${query.dbAllVars.map(_.identifier).mkString(",")} FROM $inputTable WHERE ${filters.toSqlWhere}"

      val sqlQuery = shadowOffset.fold(selectNoPaging) { o =>
        selectNoPaging + s" EXCEPT ALL (" + selectNoPaging + s" OFFSET ${o.start} LIMIT ${o.count})"
      }

      FeaturesQuery(dbVariables, dbCovariables, dbGrouping, inputTable, sqlQuery)
    }
  }

}
