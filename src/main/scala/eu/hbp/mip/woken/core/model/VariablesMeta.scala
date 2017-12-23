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

import eu.hbp.mip.woken.cromwell.core.ConfigUtil._
import cats.implicits._
import org.slf4j.{ Logger, LoggerFactory }
import spray.json._

// TODO: defaultHistogramGroupings: List[String]
case class VariablesMeta(id: Int,
                         source: String,
                         hierarchy: JsObject,
                         targetFeaturesTable: String,
                         defaultHistogramGroupings: String) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  def selectVariablesMeta(variables: List[String]): Validation[JsObject] = {

    def scanVariables(variable: String, groups: JsObject): Option[JsObject] =
      if (groups.fields.contains("variables")) {
        groups.fields("variables") match {
          case a: JsArray =>
            a.elements.toStream
              .map(_.asJsObject)
              .find(
                v =>
                  v.fields.get("code") match {
                    case Some(JsString(code)) if code == variable => true
                    case _                                        => false
                }
              )
          case _ =>
            log.error("JsArray expected")
            None
        }
      } else None

    def scanGroups(variable: String, groups: JsObject): Option[JsObject] =
      if (groups.fields.contains("groups")) {
        groups.fields("groups") match {
          case a: JsArray =>
            a.elements
              .map(g => getVariableMetaData(variable, g.asJsObject))
              .collectFirst { case Some(varMeta) => varMeta }
          case _ =>
            log.error("JsArray expected")
            None
        }
      } else None

    /**
      * Parse the tree of groups to find the variables meta data!
      * Temporary... We need to separate groups from variable meta!
      * @return
      */
    def getVariableMetaData(variable: String, groups: JsObject): Option[JsObject] =
      scanVariables(variable, groups).fold(
        scanGroups(variable, groups)
      )(
        Option(_)
      )

    val results = variables.map(v => v -> getVariableMetaData(v, hierarchy))
    if (results.exists(p => p._2.isEmpty)) {
      val variablesNotFound = results.collect { case (v, None) => v }
      s"Cannot not find metadata for ${variablesNotFound.mkString(", ")}".invalidNel
    } else
      lift(JsObject(results.map { case (v, m) => (v, m.get) }.toMap))
  }

}
