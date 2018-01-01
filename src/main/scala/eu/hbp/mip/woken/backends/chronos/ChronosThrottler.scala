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

package eu.hbp.mip.woken.backends.chronos

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.stream._
import akka.stream.scaladsl.{ Sink, Source }
import eu.hbp.mip.woken.config.JobsConfiguration

import scala.concurrent.duration._

object ChronosThrottler {

  // Requests
  // Incoming messages are simply ChronosService messages to forward to the ChronosService
  type Request = ChronosService.Request

  def props(jobsConfig: JobsConfiguration)(implicit materializer: Materializer): Props =
    Props(new ChronosThrottler(jobsConfig, materializer))

}

/**
  * Throttles the rate of messages sent to Chronos as Chronos + Zookeeper doesn't seem to handle gracefully bursts of messages.
  *
  * @param jobsConfig Configuration for the jobs, used to setup the connection to Chronos
  * @param materializer The stream materializer
  */
class ChronosThrottler(jobsConfig: JobsConfiguration, implicit val materializer: Materializer)
    extends Actor
    with ActorLogging {

  private var chronosWorker: ActorRef = _
  private var throttler: ActorRef     = _

  override def preStart(): Unit = {
    chronosWorker = context.actorOf(ChronosService.props(jobsConfig), "chronos")
    throttler = Source
      .actorRef(1000, OverflowStrategy.fail)
      .throttle(1, 100.milliseconds, 1, ThrottleMode.shaping)
      .to(Sink.actorRef(chronosWorker, NotUsed))
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
      .run()
    context.watch(chronosWorker)
    context.watch(throttler)
  }

  override def postRestart(reason: Throwable): Unit = ()

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    postStop()

  override def receive: Receive = {
    // Cleanup requests do not need to be rate-limited
    case cleanup: ChronosService.Cleanup =>
      chronosWorker ! cleanup
    // But all other requests should be rate-limited
    case request: ChronosService.Request =>
      throttler ! request
    case Terminated(ref) =>
      log.debug("Terminating ChronosThrottler as child is terminated: {}", ref)
      context.stop(self)
    case e => log.error("Unknown msg received: {}", e)
  }
}
