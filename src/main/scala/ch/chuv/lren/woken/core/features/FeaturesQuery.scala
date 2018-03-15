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

/**
  * A query for selecting data features in a table
  *
  * @param dbVariables List of variables (dependent features)
  * @param dbCovariables List of covariables (independent features)
  * @param dbGrouping List of fields to use in a group by statement (or equivalent when an algorithm supporting grouping is used)
  * @param dbTable Database table containing the data
  * @param sql Full SQL query, ready for execution
  */
case class FeaturesQuery(
    dbVariables: List[String],
    dbCovariables: List[String],
    dbGrouping: List[String],
    dbTable: String,
    sql: String
)
