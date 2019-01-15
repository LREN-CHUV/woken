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

import java.time.OffsetDateTime

import akka.actor.ActorRef
import cats.Id
import cats.effect.IO
import cats.implicits._
import ch.chuv.lren.woken.backends.faas.AlgorithmExecutor.TaggedS
import ch.chuv.lren.woken.core.model.jobs.{ DockerJob, ErrorJobResult, PfaJobResult }
import sup.{ Health, HealthCheck, mods }
import spray.json._

object AlgorithmExecutorInstances {

  def algorithmFailingWithError(errorMessage: String): AlgorithmExecutor[IO] =
    new AlgorithmExecutor[IO] {
      override def node: String = "TestNode"
      override def execute(job: DockerJob, initiator: ActorRef): IO[AlgorithmResults] =
        errorResponse(job, errorMessage, initiator).pure[IO]
      override def healthCheck: HealthCheck[IO, TaggedS] =
        HealthCheck.const[IO, Id](Health.healthy).through(mods.tagWith("Health of mock executor"))
    }

  def expectedAlgorithm(expectedAlgorithm: String): AlgorithmExecutor[IO] =
    new AlgorithmExecutor[IO] {
      override def node: String = "TestNode"
      private val pfa =
        """
           {
             "input": {},
             "output": {},
             "action": [],
             "cells": {}
           }
        """.stripMargin.parseJson.asJsObject
      override def execute(job: DockerJob, initiator: ActorRef): IO[AlgorithmResults] =
        if (job.algorithmSpec.code == expectedAlgorithm) {
          AlgorithmResults(
            job,
            List(
              PfaJobResult(job.jobId,
                           "testNode",
                           Set(),
                           OffsetDateTime.now(),
                           job.algorithmSpec.code,
                           pfa)
            ),
            initiator
          ).pure[IO]
        } else
          errorResponse(job, s"Unexpected algorithm: ${job.algorithmSpec.code}", initiator).pure[IO]

      override def healthCheck: HealthCheck[IO, TaggedS] =
        HealthCheck.const[IO, Id](Health.healthy).through(mods.tagWith("Health of mock executor"))
    }

  private def errorResponse(job: DockerJob, msg: String, initiator: ActorRef) =
    AlgorithmResults(
      job,
      List(
        ErrorJobResult(Some(job.jobId),
                       "testNode",
                       Set(),
                       OffsetDateTime.now(),
                       Some(job.algorithmSpec.code),
                       msg)
      ),
      initiator
    )

}
