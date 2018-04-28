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

package ch.chuv.lren.woken.service

import spray.json._

import scala.io.Source

// TODO: merge/provide with AlgorithmLookup ?

class AlgorithmLibraryService {

  // TODO Gather this information from all the containers
  val algorithms: JsObject =
    Source.fromURL(getClass.getResource("/algorithms.json")).mkString.parseJson.asJsObject

}

object AlgorithmLibraryService {

  def apply(): AlgorithmLibraryService = new AlgorithmLibraryService()

}
