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

import akka.actor.{ ActorRef, ActorSystem, DeadLetter }
import akka.cluster.Cluster
import akka.pattern.{ Backoff, BackoffSupervisor }
import akka.stream._
import ch.chuv.lren.woken.backends.chronos.ChronosThrottler
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.monitoring.DeadLetterMonitorActor
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor }
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Core is type containing the ``system: ActorSystem`` member. This enables us to use it in our
  * apps as well as in our tests.
  */
trait Core {

  implicit def system: ActorSystem
  implicit def actorMaterializer: ActorMaterializer
  implicit def executionContext: ExecutionContext

  def cluster: Cluster

  def mainRouter: ActorRef

}

/**
  * This trait contains the actors that make up our application; it can be mixed in with
  * ``BootedCore`` for running code or ``TestKit`` for unit and integration tests.
  */
trait CoreActors {
  this: Core with LazyLogging =>

  protected def config: WokenConfiguration

  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      logger.error(err.getMessage, err)
      Supervision.Resume
    case err =>
      logger.error("Unknown error. Stopping the stream.", err)
      Supervision.Stop
  }

  /**
    * Construct the ActorSystem we will use in our application
    */
  override lazy implicit val system: ActorSystem =
    ActorSystem(config.app.clusterSystemName, config.config)

  protected lazy implicit val actorMaterializer: ActorMaterializer = ActorMaterializer(
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
    system.eventStream.subscribe(deadLetterMonitorActor, classOf[DeadLetter])
  }

}
