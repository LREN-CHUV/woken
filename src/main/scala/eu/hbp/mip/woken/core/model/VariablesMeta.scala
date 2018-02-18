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

import ch.chuv.lren.woken.messages.variables.{ GroupMetaData, VariableMetaData }
import com.typesafe.scalalogging.LazyLogging
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.{ Validation, lift }
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
