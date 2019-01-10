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

package ch.chuv.lren.woken.backends.faas

import akka.actor.ActorRef
import ch.chuv.lren.woken.core.model.jobs.{ DockerJob, JobResult }
import sup.HealthCheck
import sup.data.Tagged

import scala.language.higherKinds

case class AlgorithmResults(job: DockerJob, results: List[JobResult], initiator: ActorRef)

object AlgorithmExecutor {
  type TaggedS[H] = Tagged[String, H]
}

import AlgorithmExecutor.TaggedS

trait AlgorithmExecutor[F[_]] {

  /**
    * Name of the current node (or cluster) where Docker containers are executed
    */
  def node: String
  def execute(job: DockerJob, initiator: ActorRef): F[AlgorithmResults]
  def healthCheck: HealthCheck[F, TaggedS]

}
