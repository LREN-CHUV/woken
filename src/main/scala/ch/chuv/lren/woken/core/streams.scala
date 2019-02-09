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

import akka.event.Logging
import akka.stream.Attributes

object streams {

  /**
    * Stream attribute used to log stream elements at debug level
    */
  val debugElements: Attributes =
    Attributes.logLevels(
      onElement = Logging.DebugLevel,
      onFinish = Logging.InfoLevel,
      onFailure = Logging.ErrorLevel
    )

}
