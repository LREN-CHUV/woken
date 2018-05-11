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

import akka.actor.{ Actor, ActorContext, ActorRef, PoisonPill, Props }
import akka.pattern.ask
import ch.chuv.lren.woken.core.model.{ DockerJob, ErrorJobResult, PfaJobResult }
import spray.json._
import CoordinatorActor._
import akka.util.Timeout
import ch.chuv.lren.woken.core.commands.JobCommands
import ch.chuv.lren.woken.core.commands.JobCommands.StartCoordinatorJob

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object FakeCoordinatorActor {

  def props(expectedAlgorithm: String, errorMessage: Option[String]): Props =
    Props(new FakeCoordinatorActor(expectedAlgorithm, errorMessage))

  def executeJobAsync(props: Props, context: ActorContext): ExecuteJobAsync = job => {
    val worker = context.actorOf(props)

    implicit val askTimeout: Timeout = Timeout(1 day)

    (worker ? StartCoordinatorJob(job, Actor.noSender, Actor.noSender))
      .mapTo[CoordinatorActor.Response]
  }

  def propsForFailingWithMsg(errorMessage: String): Props =
    Props(new FakeCoordinatorActor("", Some(errorMessage)))

  def executeFailingJobAsync(errorMessage: String): CoordinatorActor.ExecuteJobAsync =
    job =>
      Future(
        Response(job,
                 List(
                   ErrorJobResult(Some(job.jobId),
                                  "testNode",
                                  OffsetDateTime.now(),
                                  Some(job.algorithmSpec.code),
                                  errorMessage)
                 ),
                 Actor.noSender)
    )

}

class FakeCoordinatorActor(expectedAlgorithm: String, errorMessage: Option[String]) extends Actor {

  override def receive: PartialFunction[Any, Unit] = {
    case JobCommands.StartCoordinatorJob(job, replyTo, initiator) =>
      startCoordinatorJob(if (replyTo == Actor.noSender) sender() else replyTo, job, initiator)
  }

  def startCoordinatorJob(originator: ActorRef, job: DockerJob, initiator: ActorRef): Unit = {
    val pfa =
      """
           {
             "input": [],
             "output": [],
             "action": [],
             "cells": []
           }
        """.stripMargin.parseJson.asJsObject

    errorMessage.fold {
      if (job.algorithmSpec.code == expectedAlgorithm) {
        originator ! Response(
          job,
          List(
            PfaJobResult(job.jobId, "testNode", OffsetDateTime.now(), job.algorithmSpec.code, pfa)
          ),
          initiator
        )
      } else
        originator ! errorResponse(job,
                                   s"Unexpected algorithm: ${job.algorithmSpec.code}",
                                   initiator)
    } { msg =>
      originator ! errorResponse(job, msg, initiator)
    }
    self ! PoisonPill
  }

  private def errorResponse(job: DockerJob, msg: String, initiator: ActorRef) =
    Response(
      job,
      List(
        ErrorJobResult(Some(job.jobId),
                       "testNode",
                       OffsetDateTime.now(),
                       Some(job.algorithmSpec.code),
                       msg)
      ),
      initiator
    )

}
