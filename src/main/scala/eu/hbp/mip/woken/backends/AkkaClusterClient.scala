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

package eu.hbp.mip.woken.backends

import akka.pattern._
import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.client.{ ClusterClient, ClusterClientSettings }
import akka.util.Timeout
import eu.hbp.mip.woken.config.RemoteLocation
import eu.hbp.mip.woken.messages.query.{ Query, QueryResult }
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
