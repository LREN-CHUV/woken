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

import java.time.OffsetDateTime

import akka.actor.{ Actor, ActorRef, PoisonPill }
import ch.chuv.lren.woken.core.model.PfaJobResult
import spray.json._
import CoordinatorActor._
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.core.commands.JobCommands

class FakeCoordinatorActor() extends Actor {

  override def receive: PartialFunction[Any, Unit] = {
    case JobCommands.StartCoordinatorJob(job) =>
      startCoordinatorJob(sender(), job)
  }

  def startCoordinatorJob(originator: ActorRef, job: DockerJob): Unit = {
    val pfa =
      """
           {
             "input": [],
             "output": [],
             "action": [],
             "cells": []
           }
        """.stripMargin.parseJson.asJsObject

    originator ! Response(
      job,
      List(
        PfaJobResult(job.jobId, "testNode", OffsetDateTime.now(), job.algorithmSpec.code, pfa)
      )
    )
    self ! PoisonPill
  }

}
