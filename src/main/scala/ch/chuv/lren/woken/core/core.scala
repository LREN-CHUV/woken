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

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.{ Backoff, BackoffSupervisor }
import akka.stream._
import cats.data.NonEmptyList
import com.typesafe.config.{ Config, ConfigFactory }
import ch.chuv.lren.woken.backends.chronos.ChronosThrottler
import ch.chuv.lren.woken.config.JobsConfiguration
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Core is type containing the ``system: ActorSystem`` member. This enables us to use it in our
  * apps as well as in our tests.
  */
trait Core {

  protected implicit def system: ActorSystem

  protected def config: Config

  protected def jobsConf: JobsConfiguration
  protected def mainRouter: ActorRef

}

/**
  * This trait contains the actors that make up our application; it can be mixed in with
  * ``BootedCore`` for running code or ``TestKit`` for unit and integration tests.
  */
trait CoreActors {
  this: Core with LazyLogging =>

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  protected def configurationFailed[B](e: NonEmptyList[String]): B =
    throw new IllegalStateException(s"Invalid configuration: ${e.toList.mkString(", ")}")

  protected lazy val config: Config =
    ConfigFactory
      .parseString("""
        |akka {
        |  actor.provider = cluster
        |  extensions += "akka.cluster.pubsub.DistributedPubSub"
        |  extensions += "akka.cluster.client.ClusterClientReceptionist"
        |}
      """.stripMargin)
      .withFallback(ConfigFactory.parseResourcesAnySyntax("akka.conf"))
      .withFallback(ConfigFactory.parseResourcesAnySyntax("algorithms.conf"))
      .withFallback(ConfigFactory.parseResourcesAnySyntax("kamon.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()

  config.origin()
  protected lazy val jobsConf: JobsConfiguration = JobsConfiguration
    .read(config)
    .valueOr(configurationFailed)

  val decider: Supervision.Decider = {
    case err: RuntimeException =>
      logger.error(err.getMessage)
      Supervision.Resume
    case _ =>
      logger.error("Unknown error. Stopping the stream. ")
      Supervision.Stop
  }
  protected lazy implicit val actorMaterializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(system).withSupervisionStrategy(decider)
  )

  private val chronosSupervisorProps = BackoffSupervisor.props(
    Backoff.onFailure(
      ChronosThrottler.props(jobsConf),
      childName = "chronosThrottler",
      minBackoff = 1 second,
      maxBackoff = 30 seconds,
      randomFactor = 0.2
    )
  )

  lazy val chronosHttp: ActorRef = system.actorOf(chronosSupervisorProps, "chronosSupervisor")

  def beforeBoot(): Unit    = ()
  def startActors(): Unit   = ()
  def startServices(): Unit = ()

}
