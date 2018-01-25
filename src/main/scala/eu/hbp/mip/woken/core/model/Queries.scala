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

package eu.hbp.mip.woken.core.model

import eu.hbp.mip.woken.messages.query.Query
import eu.hbp.mip.woken.messages.variables.VariableId
import org.postgresql.core.Utils

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

  implicit class QueryEnhanced(val query: Query) extends AnyVal {

    /** Convert variable to lowercase as Postgres returns lowercase fields in its result set
      * Variables codes are sanitized to ensure valid database field names using the following conversions:
      * + replace - by _
      * + prepend _ to the variable name if it starts by a number
      */
    private[this] def toField(v: VariableId) =
      v.code.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1")

    def dbAllVars: List[String] =
      (query.variables ++ query.covariables ++ query.grouping).distinct.map(toField)

    def dbVariables: List[String]   = query.variables.map(toField)
    def dbCovariables: List[String] = query.covariables.map(toField)
    def dbGrouping: List[String]    = query.grouping.map(toField)

  }

}
