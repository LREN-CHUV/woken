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

package ch.chuv.lren.woken.core.commands

import akka.actor.ActorRef
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.core.ExperimentActor.Job

object JobCommands {

  sealed trait Command

  /**
    * Start mining command.
    * @param job - docker job
    * @param replyTo Actor to reply to. Can be Actor.noSender when the ask pattern is used. This information is added in preparation for Akka Typed
    * @param initiator The initiator of the request, this information will be returned by CoordinatorActor.Response#initiator.
    *                  It can also have the value Actor.noSender
    */
  case class StartCoordinatorJob(job: DockerJob, replyTo: ActorRef, initiator: ActorRef)
      extends Command

  /**
    * Start a new experiment job.
    * @param job - experiment job
    * @param replyTo Actor to reply to. Can be Actor.noSender when the ask pattern is used. This information is added in preparation for Akka Typed
    * @param initiator The initiator of the request, this information will be returned by CoordinatorActor.Response#initiator.
    *                  It can also have the value Actor.noSender
    */
  case class StartExperimentJob(job: Job, replyTo: ActorRef, initiator: ActorRef) extends Command

}
