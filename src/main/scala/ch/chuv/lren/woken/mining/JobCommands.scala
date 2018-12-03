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

package ch.chuv.lren.woken.mining

import akka.actor.ActorRef
import ch.chuv.lren.woken.core.model
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs.DockerJob
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, ExperimentQuery }
import ch.chuv.lren.woken.messages.variables.VariableMetaData

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

case class ExperimentJob(
    jobId: String,
    inputDb: String,
    inputDbSchema: Option[String],
    inputTable: String,
    query: ExperimentQuery,
    algorithms: Map[AlgorithmSpec, AlgorithmDefinition],
    metadata: List[VariableMetaData]
) extends model.jobs.Job {

  def definitionOf(algorithm: AlgorithmSpec): AlgorithmDefinition =
    algorithms.getOrElse(algorithm,
                         throw new IllegalStateException(
                           s"Expected a definition matching algorithm spec $algorithm"
                         ))

}

/**
  * Start a new experiment job.
  * @param job - experiment job
  * @param replyTo Actor to reply to. Can be Actor.noSender when the ask pattern is used. This information is added in preparation for Akka Typed
  * @param initiator The initiator of the request, this information will be returned by CoordinatorActor.Response#initiator.
  *                  It can also have the value Actor.noSender
  */
case class StartExperimentJob(job: ExperimentJob, replyTo: ActorRef, initiator: ActorRef)
    extends Command
