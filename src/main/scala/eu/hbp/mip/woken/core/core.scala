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

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.contrib.throttle.{ Throttler, TimerBasedThrottler }
import com.typesafe.config.ConfigFactory
import eu.hbp.mip.woken.backends.chronos.ChronosService
import eu.hbp.mip.woken.config.JobsConfiguration
import scala.concurrent.duration._

/**
  * Core is type containing the ``system: ActorSystem`` member. This enables us to use it in our
  * apps as well as in our tests.
  */
trait Core {

  protected implicit def system: ActorSystem

}

/**
  * This trait contains the actors that make up our application; it can be mixed in with
  * ``BootedCore`` for running code or ``TestKit`` for unit and integration tests.
  */
trait CoreActors {
  this: Core =>

  // TODO: improve passing configuration around
  private lazy val config = ConfigFactory.load()
  private lazy val jobsConf = JobsConfiguration
    .read(config)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))

  private val chronosActor: ActorRef = system.actorOf(ChronosService.props(jobsConf), "chronos")

  import Throttler._
  // The throttler for this example, setting the rate
  val chronosHttp: ActorRef = system.actorOf(
    Props(classOf[TimerBasedThrottler], 1 msgsPer 300.millisecond),
    "rateLimit.chronos"
  )

  // Set the target
  chronosHttp ! SetTarget(Some(chronosActor))

}
