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

package eu.hbp.mip.woken.json

import spray.json.{ JsArray, JsBoolean, JsNull, JsNumber, JsObject, JsString, JsValue }
import org.yaml.snakeyaml.{ Yaml => YamlParser }

object yaml {

  case class Yaml(yaml: String) extends AnyRef

  /**
    * Takes a YAML string and maps it to a Spray JSON AST
    */
  val yaml2Json: (Yaml) => JsValue = load _ andThen asSprayJSON

  private def load(input: Yaml): AnyRef = new YamlParser().load(input.yaml)

  import scala.collection.JavaConverters._

  private def asSprayJSON(obj: Object): JsValue = obj match {
    case x: java.util.Map[Object @unchecked, Object @unchecked] =>
      JsObject(x.asScala.map { case (k, v) => k.toString -> asSprayJSON(v) }.toMap)
    case x: java.util.List[Object @unchecked] =>
      JsArray(x.asScala.map(asSprayJSON).toVector)
    case x: java.util.Set[Object @unchecked] =>
      JsArray(x.asScala.map(asSprayJSON).toVector)
    case i: java.lang.Integer =>
      JsNumber(BigDecimal(i))
    case i: java.lang.Long =>
      JsNumber(BigDecimal(i))
    case i: java.math.BigInteger =>
      JsNumber(BigDecimal(i))
    case i: java.lang.Double =>
      JsNumber(BigDecimal(i))
    case s: java.lang.String =>
      JsString(s)
    case d: java.util.Date =>
      JsString(d.toString)
    case b: java.lang.Boolean =>
      JsBoolean(b)
    case _ => JsNull
  }

}
