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

package ch.chuv.lren.woken.akka

import akka.actor.{ ActorRef, DeadLetter }
import akka.pattern.{ Backoff, BackoffSupervisor }
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Supervision }
import ch.chuv.lren.woken.akka.monitoring.DeadLetterMonitorActor
import ch.chuv.lren.woken.backends.chronos.ChronosThrottler
import ch.chuv.lren.woken.config.WokenConfiguration
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * This trait contains the actors that make up our application; it can be mixed in with
  * ``BootedCore`` for running code or ``TestKit`` for unit and integration tests.
  */
trait CoreActors {
  this: CoreSystem =>

  protected def logger: Logger
  protected def config: WokenConfiguration

  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      logger.error(err.getMessage, err)
      Supervision.Resume
    case err =>
      logger.error("Unknown error. Stopping the actor or stream.", err)
      Supervision.Stop
  }

  override lazy implicit val actorMaterializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  )

  override lazy implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  lazy val chronosHttp: ActorRef = {
    val chronosSupervisorProps = BackoffSupervisor.props(
      Backoff.onFailure(
        ChronosThrottler.props(config.jobs),
        childName = "chronosThrottler",
        minBackoff = 1 second,
        maxBackoff = 30 seconds,
        randomFactor = 0.2
      )
    )

    system.actorOf(chronosSupervisorProps, "chronosSupervisor")
  }

  def monitorDeadLetters(): Unit = {
    val deadLetterMonitorActor =
      system.actorOf(DeadLetterMonitorActor.props, name = "deadLetterMonitor")

    // Subscribe to system wide event bus 'DeadLetter'
    if (!system.eventStream.subscribe(deadLetterMonitorActor, classOf[DeadLetter])) {
      logger.warn("Cannot monitor Akka dead letter events")
    }
  }

}
