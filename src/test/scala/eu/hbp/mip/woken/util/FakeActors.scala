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

package eu.hbp.mip.woken.util

import akka.actor.{ Actor, Props }

object FakeActors {

  /**
    * EchoActor sends back received messages (unmodified).
    */
  class EchoActor extends Actor {
    override def receive: PartialFunction[Any, Unit] = {
      case message =>
        Thread.sleep(300)
        sender() ! message
        context.stop(self)
    }
  }

  val echoActorProps: Props = Props[EchoActor]()
}
