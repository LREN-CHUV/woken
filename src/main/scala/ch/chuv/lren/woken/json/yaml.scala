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

package ch.chuv.lren.woken.json

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
