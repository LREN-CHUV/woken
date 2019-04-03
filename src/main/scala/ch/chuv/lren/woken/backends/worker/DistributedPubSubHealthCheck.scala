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

import akka.cluster.Cluster
import akka.cluster.pubsub.{ DistributedPubSub, DistributedPubSubMediator }
import akka.pattern.ask
import akka.util.Timeout
import cats.Id
import cats.effect.{ Effect, Sync }
import cats.implicits._
import ch.chuv.lren.woken.core.fp._
import ch.chuv.lren.woken.messages.{ Ping, Pong }
import com.typesafe.scalalogging.Logger
import sup.HealthCheck

import scala.concurrent.duration._
import scala.language.higherKinds

object DistributedPubSubHealthCheck {

  private val logger: Logger = Logger("woken.DistributedPubSubHealthCheck")

  def check[F[_]: Effect](pubSub: DistributedPubSub, cluster: Cluster, path: String, role: String)(
      timeout: FiniteDuration
  ): HealthCheck[F, Id] =
    HealthCheck.liftFBoolean {
      lazy val gracePeriod = 5.minutes.fromNow

      // Defer computation to return a different result on every execution
      Sync[F].defer {
        if (gracePeriod.hasTimeLeft()) {
          true.pure[F]
        } else if (!cluster.state.members.exists(m => m.roles.contains(role))) {
          val clusterRoles = cluster.state.members.flatMap(_.roles).mkString(",")
          logger.warn(
            s"Cannot find a member in the cluster with role $role, found roles $clusterRoles"
          )
          false.pure[F]
        } else {
          val response: F[Pong] = fromFuture {
            implicit val askTimeout: Timeout = Timeout(timeout)
            (pubSub.mediator ? DistributedPubSubMediator.Send(path,
                                                              Ping(Some(role)),
                                                              localAffinity = false))
              .mapTo[Pong]
          }
          response
            .map(_ => true)
            .recoverWith {
              case e =>
                logger.warn(s"Cannot check remote actor $path", e)
                false.pure[F]
            }
        }
      }
    }

  def checkValidation[F[_]: Effect](pubSub: DistributedPubSub,
                                    cluster: Cluster): HealthCheck[F, Id] =
    check[F](pubSub, cluster, "/user/validation", "validation")(30.seconds)

  def checkScoring[F[_]: Effect](pubSub: DistributedPubSub, cluster: Cluster): HealthCheck[F, Id] =
    check[F](pubSub, cluster, "/user/scoring", "scoring")(30.seconds)

}
