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

package eu.hbp.mip.woken.api

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import spray.http.StatusCodes._
import spray.httpx.marshalling.ToResponseMarshaller
import spray.routing.RequestContext
import akka.actor.OneForOneStrategy

import scala.concurrent.duration._
import spray.http.StatusCode
import eu.hbp.mip.woken.core._
import eu.hbp.mip.woken.messages.Error

trait PerRequest extends Actor with ActorLogging {

  import context._
  import DefaultMarshallers._

  def r: RequestContext
  def target: ActorRef
  def message: RestMessage

  setReceiveTimeout(180.seconds) // TODO: make configurable, align with spray.can.timeout
  target ! message

  // TODO: status code parameter redundant

  def receive: PartialFunction[Any, Unit] = {
    case res: RestMessage =>
      complete(OK, res)(res.marshaller.asInstanceOf[ToResponseMarshaller[RestMessage]])
// TODO    case v: Error       => complete(BadRequest, v)
    // TODO    case ReceiveTimeout => complete(GatewayTimeout, Error("Request timeout"))
    case e: Any => log.error(s"Unhandled message: $e")
  }

  def complete[T <: AnyRef](status: StatusCode,
                            obj: T)(implicit marshaller: ToResponseMarshaller[T]) = {
    r.complete(obj)(marshaller)
    stop(self)
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy() {
      case e => {
        // TODO: was Error(e.getMessage)
        complete(InternalServerError, e.getMessage)
        Stop
      }
    }
}

object PerRequest {
  case class WithActorRef(r: RequestContext, target: ActorRef, message: RestMessage)
      extends PerRequest

  case class WithProps(r: RequestContext, props: Props, message: RestMessage) extends PerRequest {
    lazy val target: ActorRef = context.actorOf(props)
  }
}

trait PerRequestCreator {

  import PerRequest._

  def context: ActorRefFactory

  def perRequest(r: RequestContext, target: ActorRef, message: RestMessage): ActorRef =
    context.actorOf(Props(WithActorRef(r, target, message)))

  def perRequest(r: RequestContext, props: Props, message: RestMessage): ActorRef =
    context.actorOf(Props(WithProps(r, props, message)))
}
