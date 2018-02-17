/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.core

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.{ Backoff, BackoffSupervisor }
import akka.stream._
import cats.data.NonEmptyList
import com.typesafe.config.{ Config, ConfigFactory }
import eu.hbp.mip.woken.backends.chronos.ChronosThrottler
import eu.hbp.mip.woken.config.JobsConfiguration

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
  this: Core =>

  protected def configurationFailed[B](e: NonEmptyList[String]): B =
    throw new IllegalStateException(s"Invalid configuration: ${e.toList.mkString(", ")}")

  protected lazy val config: Config =
    ConfigFactory.parseString("""
        |akka {
        |  actor.provider = cluster
        |  extensions += "akka.cluster.pubsub.DistributedPubSub"
        |  extensions += "akka.cluster.client.ClusterClientReceptionist"
        |}
      """.stripMargin).withFallback(ConfigFactory.load()).resolve()
  protected lazy val jobsConf: JobsConfiguration = JobsConfiguration
    .read(config)
    .valueOr(configurationFailed)

  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  private val chronosSupervisorProps = BackoffSupervisor.props(
    Backoff.onFailure(
      ChronosThrottler.props(jobsConf),
      childName = "chronosThrottler",
      minBackoff = 1 second,
      maxBackoff = 30 seconds,
      randomFactor = 0.2
    )
  )

  val chronosHttp: ActorRef = system.actorOf(chronosSupervisorProps, "chronosSupervisor")

}
