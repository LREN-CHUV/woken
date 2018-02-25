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

package ch.chuv.lren.woken.backends

import akka.pattern._
import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.client.{ ClusterClient, ClusterClientSettings }
import akka.util.Timeout
import ch.chuv.lren.woken.messages.query.{ Query, QueryResult }
import ch.chuv.lren.woken.messages.remoting.RemoteLocation

import scala.concurrent.duration._
import scala.concurrent.Future

object AkkaClusterClient {

  implicit val timeout: Timeout = Timeout(180.seconds)

  def sendReceive(location: RemoteLocation,
                  query: Query)(implicit system: ActorSystem): Future[QueryResult] = {

    val cluster: ActorRef =
      system.actorOf(ClusterClient.props(ClusterClientSettings(system)), "client")
    val entryPoint = "/user/entrypoint"

    (cluster ? ClusterClient.Send(entryPoint, query, localAffinity = true))
      .mapTo[QueryResult]
  }

}
