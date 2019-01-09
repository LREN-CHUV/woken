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

package ch.chuv.lren.woken.backends.worker

import akka.actor.ActorRef
import akka.cluster.pubsub.DistributedPubSubMediator
import akka.pattern.ask
import akka.util.Timeout
import cats.Id
import cats.data.NonEmptyList
import cats.effect.{ Effect, IO }
import cats.implicits._
import ch.chuv.lren.woken.messages.validation.{
  ScoringQuery,
  ScoringResult,
  ValidationQuery,
  ValidationResult
}
import ch.chuv.lren.woken.messages.variables.{ VariableMetaData, VariableType }
import com.typesafe.scalalogging.Logger
import eu.timepit.refined.types.numeric.PosInt
import eu.timepit.refined.auto._
import org.slf4j.LoggerFactory
import spray.json.{ JsObject, JsString }
import sup.HealthCheck

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.reflect.ClassTag

// TODO: Pubsub checks should use a simple Ping message
object DistributedPubSubHealthCheck {

  private val logger: Logger = Logger(LoggerFactory.getLogger("woken.DistributedPubSubHealthCheck"))

  private def check[F[_]: Effect, A, B](
      mediator: ActorRef,
      path: String,
      request: A
  )(timeoutSeconds: Option[PosInt])(implicit classTag: ClassTag[B]): HealthCheck[F, Id] = {
    implicit val askTimeout: Timeout = Timeout(timeoutSeconds.fold(0)(_.value).seconds)
    val topicsFuture: Future[B] =
      (mediator ? DistributedPubSubMediator.Send(path, request, localAffinity = false)).mapTo[B]
    val responseIO: IO[B] = IO.fromFuture(IO.pure(topicsFuture))
    val result            = responseIO.attempt.unsafeRunSync()
    logger.info("Found validation instance: {}", result)
    HealthCheck.liftFBoolean(result.isRight.pure[F])
  }

  def checkValidation[F[_]: Effect](mediator: ActorRef): HealthCheck[F, Id] = {
    val query = ValidationQuery(
      0,
      JsObject(Map("test" -> JsString("test"))),
      List.empty,
      VariableMetaData("alzheimerbroadcategory",
                       "alzheimerbroadcategory",
                       VariableType.text,
                       None,
                       None,
                       None,
                       None,
                       None,
                       None,
                       None,
                       None,
                       None,
                       Set())
    )
    check[F, ValidationQuery, ValidationResult](mediator, "/user/validation", query)(Some(5))
  }

  def checkScoring[F[_]: Effect](mediator: ActorRef): HealthCheck[F, Id] = {
    val query = ScoringQuery(
      algorithmOutput = NonEmptyList(JsString("test"), List.empty),
      groundTruth = NonEmptyList(JsString("test"), List.empty),
      targetMetaData = VariableMetaData("alzheimerbroadcategory",
                                        "alzheimerbroadcategory",
                                        VariableType.text,
                                        None,
                                        None,
                                        None,
                                        None,
                                        None,
                                        None,
                                        None,
                                        None,
                                        None,
                                        Set())
    )
    check[F, ScoringQuery, ScoringResult](mediator, "/user/scoring", query)(Some(5))
  }

}
