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

package ch.chuv.lren.woken.akka.monitoring

import akka.actor.{ Actor, DeadLetter, Props }
import com.typesafe.scalalogging.LazyLogging
import ch.chuv.lren.woken.errors._

object DeadLetterMonitorActor {
  def props: Props = Props(new DeadLetterMonitorActor())
}

class DeadLetterMonitorActor extends Actor with LazyLogging {

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.NonUnitStatements"))
  def receive: Receive = {
    case d: DeadLetter =>
      logger.error(SKIP_REPORTING_MARKER, s"Saw dead letter $d")

    case _ =>
      logger.debug("DeadLetterMonitorActor: got a message")

  }
}
