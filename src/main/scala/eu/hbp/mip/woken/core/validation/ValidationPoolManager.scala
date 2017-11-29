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

package eu.hbp.mip.woken.core.validation

import akka.actor.{ Actor, ActorLogging, ActorPath, RootActorPath }
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

object ValidationPoolManager {
  // TODO: remove mutable pool
  val validationPool = scala.collection.mutable.Set[ActorPath]()
}

class ValidationPoolManager extends Actor with ActorLogging {

  // Upon start say "Hi" to Woken
  /* override def preStart(): Unit = {
     val remotePath = RemotePathExtension(context.system).getPath(this)
     wokenActor ! ("Hi!", remotePath)
   }*/

  val cluster = Cluster(context.system)

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit =
    cluster.subscribe(self,
                      initialStateMode = InitialStateAsEvents,
                      classOf[MemberEvent],
                      classOf[UnreachableMember])

  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive: PartialFunction[Any, Unit] = {
    case MemberUp(member) =>
      if (member.hasRole("validation")) {
        log.info("New validation node in pool: " + member)
        ValidationPoolManager.validationPool += RootActorPath(member.address) / "user" / "validation"
      }
    case UnreachableMember(member) => Unit
    case MemberRemoved(member, previousStatus) =>
      if (member.hasRole("validation"))
        ValidationPoolManager.validationPool -= RootActorPath(member.address) / "user" / "validation"
    case _: MemberEvent => // ignore
  }
}
