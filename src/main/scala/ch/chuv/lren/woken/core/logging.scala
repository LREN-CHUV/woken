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

// Portions of code copied from Doobie, under Apache2.0 copyright

package ch.chuv.lren.woken.core

import com.typesafe.scalalogging.Logger
import doobie.util.log.{ ExecFailure, LogHandler, ProcessingFailure, Success }

object logging {

  /**
    * A LogHandler that writes a default format to a JDK Logger. This is provided for demonstration
    * purposes and is not intended for production use.
    * @group Constructors
    */
  val doobieLogHandler: LogHandler = {
    val logger = Logger("woken.Database")
    LogHandler {

      case Success(s, a, e1, e2) =>
        logger.debug(s"""Successful Statement Execution:
            |
            |  ${removeEmptyLines(s)}
            |
            | arguments = [${a.mkString(", ")}]
            |   elapsed = ${e1.toMillis} ms exec + ${e2.toMillis} ms processing (${(e1 + e2).toMillis} ms total)
          """.stripMargin)

      case ProcessingFailure(s, a, e1, e2, t) =>
        logger.error(s"""Failed Resultset Processing:
            |
            |  ${removeEmptyLines(s)}
            |
            | arguments = [${a.mkString(", ")}]
            |   elapsed = ${e1.toMillis} ms exec + ${e2.toMillis} ms processing (failed) (${(e1 + e2).toMillis} ms total)
            |   failure = ${t.getMessage}
          """.stripMargin)

      case ExecFailure(s, a, e1, t) =>
        logger.error(s"""Failed Statement Execution:
            |
            |  ${removeEmptyLines(s)}
            |
            | arguments = [${a.mkString(", ")}]
            |   elapsed = ${e1.toMillis} ms exec (failed)
            |   failure = ${t.getMessage}
          """.stripMargin)

    }
  }

  private def removeEmptyLines(s: String): String =
    augmentString(s).lines.dropWhile(_.trim.isEmpty).mkString("\n  ")

}
