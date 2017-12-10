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

import spray.json._
import spray.json.DefaultJsonProtocol._

// TODO: defaultHistogramGroupings: List[String]
case class VariablesMeta(id: Int,
                         source: String,
                         hierarchy: JsObject,
                         targetFeaturesTable: String,
                         defaultHistogramGroupings: String) {

  def getMetaData(variables: Seq[String]): JsObject = {

    /**
      * Parse the tree of groups to find the variables meta data!
      * Temporary... We need to separate groups from variable meta!
      * @return
      */
    def getVariableMetaData(variable: String, groups: JsObject): Option[JsObject] = {

      if (groups.fields.contains("variables")) {
        groups.fields("variables") match {
          case a: JsArray =>
            a.elements.find(
              v =>
                v.asJsObject.fields.get("code") match {
                  case Some(stringValue) => stringValue.convertTo[String] == variable
                  case None              => false
              }
            ) match {
              case Some(value) => return Some(value.asJsObject)
              case None        => None
            }
          case _ => deserializationError("JsArray expected")
        }
      }

      if (groups.fields.contains("groups")) {
        groups.fields("groups") match {
          case a: JsArray =>
            return a.elements.toStream
              .map(g => getVariableMetaData(variable, g.asJsObject))
              .find(o => o.isDefined) match {
              case Some(variable: Option[JsObject]) => variable
              case None                             => None
            }
          case _ => deserializationError("JsArray expected")
        }
      }

      None
    }

    new JsObject(
      variables
        .map(
          v =>
            v -> (getVariableMetaData(v, hierarchy) match {
              case Some(m) => m
              case None =>
                JsObject("error" -> JsString(s"Cannot not find metadata for $v"))
            })
        )
        .toMap
    )
  }

}
