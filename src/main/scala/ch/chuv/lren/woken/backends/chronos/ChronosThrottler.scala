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

package ch.chuv.lren.woken.backends.chronos

import akka.NotUsed
import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.stream._
import akka.stream.scaladsl.{ Sink, Source }
import ch.chuv.lren.woken.config.JobsConfiguration
import com.typesafe.scalalogging.LazyLogging

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
    with LazyLogging {

  private lazy val chronosWorker: ActorRef =
    context.actorOf(ChronosService.props(jobsConfig), "chronos")

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  private lazy val throttler: ActorRef = Source
    .actorRef(1000, OverflowStrategy.fail)
    .throttle(1, 100.milliseconds, 1, ThrottleMode.shaping)
    .to(Sink.actorRef(chronosWorker, NotUsed))
    .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
    .run()

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  override def preStart(): Unit = {
    context.watch(chronosWorker)
    context.watch(throttler)
  }

  override def postRestart(reason: Throwable): Unit = ()

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    postStop()

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  override def receive: Receive = {
    // Cleanup requests do not need to be rate-limited
    case cleanup: ChronosService.Cleanup =>
      chronosWorker ! cleanup
    // But all other requests should be rate-limited
    case request: ChronosService.Request =>
      throttler ! request
    case Terminated(ref) =>
      logger.debug("Terminating ChronosThrottler as child is terminated: {}", ref)
      context.stop(self)
    case e => logger.error("Unknown msg received: {}", e)
  }
}
