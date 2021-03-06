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

package ch.chuv.lren.woken.core

import spray.json._

package object json {

  import acyclic.pkg

  /**
    * Quick estimation of the weight of a Json object
    *
    * @param json The json to weight
    */
  def weightEstimate(json: JsValue): Int = json match {
    case `JsNull`          => 0
    case JsBoolean(_)      => 1
    case JsNumber(_)       => 1
    case JsString(s)       => s.length
    case JsArray(elements) => elements.map(weightEstimate).sum
    case JsObject(fields)  => fields.values.map(weightEstimate).sum
    case _                 => 0 // Cannot weight accurately, it does not matter currently
  }

}
