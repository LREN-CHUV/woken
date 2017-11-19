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

// TODO: this should move to woken-messages

package eu.hbp.mip.woken.meta

import spray.json._

// Get target variable's meta data
object MetaDataProtocol extends DefaultJsonProtocol {
  //implicit val variableMetaData = jsonFormat6(VariableMetaData)

  implicit object VariableMetaDataFormat extends RootJsonFormat[VariableMetaData] {
    // Some fields are optional so we produce a list of options and
    // then flatten it to only write the fields that were Some(..)
    def write(item: VariableMetaData): JsObject =
      JsObject(
        ((item.methodology match {
          case Some(m) => Some("methodology" -> m.toJson)
          case _       => None
        }) :: (item.units match {
          case Some(u) => Some("units" -> u.toJson)
          case _       => None
        }) :: (item.enumerations match {
          case Some(e) => Some("enumerations" -> e.map({ case (c, l) => (c, l) }).toJson)
          case _       => None
        }) :: List(
          Some("code"  -> item.code.toJson),
          Some("label" -> item.label.toJson),
          Some("type"  -> item.`type`.toJson)
        )).flatten: _*
      )

    // We use the "standard" getFields method to extract the mandatory fields.
    // For optional fields we extract them directly from the fields map using get,
    // which already handles the option wrapping for us so all we have to do is map the option
    def read(json: JsValue): VariableMetaData = {
      val jsObject = json.asJsObject

      jsObject.getFields("code", "label", "type") match {
        case Seq(code, label, t) ⇒
          VariableMetaData(
            code.convertTo[String],
            label.convertTo[String],
            t.convertTo[String],
            jsObject.fields.get("methodology").map(_.convertTo[String]),
            jsObject.fields.get("units").map(_.convertTo[String]),
            jsObject.fields
              .get("enumerations")
              .map(
                _.convertTo[JsArray].elements
                  .map(
                    o =>
                      o.asJsObject
                        .fields("code")
                        .convertTo[String] -> o.asJsObject
                        .fields("label")
                        .convertTo[String]
                  )
                  .toMap
              )
          )
        case other ⇒
          deserializationError(
            "Cannot deserialize VariableMetaData: invalid input. Raw input: " + other
          )
      }
    }
  }
}
