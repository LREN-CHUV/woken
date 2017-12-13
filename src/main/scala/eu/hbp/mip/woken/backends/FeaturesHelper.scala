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

import eu.hbp.mip.woken.messages.external.MiningQuery
import eu.hbp.mip.woken.core.model.Queries._

// TODO: merge with Queries?

case class QueryOffset(start: Int, count: Int)

object FeaturesHelper {

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
