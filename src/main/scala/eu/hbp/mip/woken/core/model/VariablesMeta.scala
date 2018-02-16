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
import com.typesafe.scalalogging.Logger

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
                         defaultHistogramGroupings: List[String]) {

  val log = Logger(getClass)

  def selectVariablesMeta(filter: String => Boolean): List[VariableMetaData] = {

    def selectVars(group: GroupMetaData): List[VariableMetaData] =
      group.groups.map(selectVars).reduce(_ ++ _) ++ group.variables.filter(v => filter(v.code))

    selectVars(hierarchy)

  }

}
