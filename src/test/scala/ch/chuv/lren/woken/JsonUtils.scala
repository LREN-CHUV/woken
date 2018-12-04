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

package ch.chuv.lren.woken
import spray.json.JsValue

import scala.io.Source

trait JsonUtils {

  def loadJson(path: String): JsValue = {
    import spray.json.DefaultJsonProtocol.RootJsObjectFormat
    import spray.json._

    val source = Source.fromURL(getClass.getResource(path))
    jsonReader
    source.mkString.parseJson
  }
}
