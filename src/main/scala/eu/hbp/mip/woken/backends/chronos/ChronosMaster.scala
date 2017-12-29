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
import akka.actor.{ Actor, ActorLogging, Props }
import akka.stream._
import akka.stream.scaladsl.{ Sink, Source }
import eu.hbp.mip.woken.backends.chronos.ChronosMaster.{ Check, Schedule }
import eu.hbp.mip.woken.config.JobsConfiguration

import scala.concurrent.duration._

object ChronosMaster {

  case class Schedule(job: ChronosJob)

  case class Check(jobId: String, job: ChronosJob)

  def props(jobsConfig: JobsConfiguration)(implicit materializer: Materializer): Props =
    Props(new ChronosMaster(jobsConfig, materializer))

}

class ChronosMaster(jobsConfig: JobsConfiguration, implicit val materializer: Materializer)
    extends Actor
    with ActorLogging {

  private val chronosWorker = context.actorOf(ChronosService.props(jobsConfig), "chronos")

  private val throttler = Source
    .actorRef(10, OverflowStrategy.fail)
    .throttle(1, 1.second, 1, ThrottleMode.shaping)
    .to(Sink.actorRef(chronosWorker, NotUsed))
    .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
    .run()

  override def receive: Receive = {
    case schedule: Schedule =>
      val originator = sender()
      throttler ! ChronosService.Schedule(schedule.job, originator)
    case check: Check =>
      val originator = sender()
      throttler ! ChronosService.Check(check.jobId, check.job, originator)
    case cleanup: ChronosService.Cleanup =>
      throttler ! cleanup
    case e => log.error("Unknown msg received: {}", e)
  }
}
