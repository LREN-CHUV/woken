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

package ch.chuv.lren.woken.core.model

import ch.chuv.lren.woken.messages.variables.{ GroupMetaData, VariableMetaData }
import com.typesafe.scalalogging.LazyLogging
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.{ Validation, lift }
import cats.syntax.validated._

/**
  * Meta description of variables
  *
  * @param id Database ID
  * @param source Owner of the list of variables and their organisation into groups
  * @param hierarchy Hierarchy of groups and variables associated with
  * @param targetFeaturesTable Name of the table containing the features described in this metadata
  * @param defaultHistogramGroupings List of groupings to apply by default when creating histograms on the features table
  */
case class VariablesMeta(id: Int,
                         source: String,
                         hierarchy: GroupMetaData,
                         targetFeaturesTable: String,
                         defaultHistogramGroupings: List[String])
    extends LazyLogging {

  def selectVariables(variables: List[String]): Validation[List[VariableMetaData]] = {
    val variablesMeta = select(variables.contains)
    if (variablesMeta.lengthCompare(variables.size) != 0) {
      val missingVars = variables.diff(variablesMeta.map(_.code))
      s"Found ${variablesMeta.size} out of ${variables.size} variables. Missing ${missingVars
        .mkString(",")}".invalidNel
    } else
      lift(variablesMeta)
  }

  def select(filter: String => Boolean): List[VariableMetaData] = {

    def selectVars(group: GroupMetaData): List[VariableMetaData] =
      group.groups.map(selectVars).reduceOption(_ ++ _).getOrElse(Nil) ++ group.variables.filter(
        v => filter(v.code)
      )

    selectVars(hierarchy)

  }

}
