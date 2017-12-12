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
import akka.actor.OneForOneStrategy
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{RequestContext, Route, RouteResult}

import scala.concurrent.duration._
import eu.hbp.mip.woken.core._
import eu.hbp.mip.woken.messages.Error

import scala.concurrent.Promise

trait PerRequest extends Actor with ActorLogging {

  import context._
  import DefaultMarshallers._

  def ctx: RequestContext

  def target: ActorRef

  def message: RestMessage

  setReceiveTimeout(180.seconds) // TODO: make configurable, align with spray.can.timeout
  target ! message

  // TODO: status code parameter redundant

  def receive: PartialFunction[Any, Unit] = {
    case res: RestMessage =>
      // TODO: add the json marshaller for RestMessage
      // r.complete(res)
      ctx.complete(OK)
    case v: Error =>
      ctx.complete((BadRequest, v.message))
    case ReceiveTimeout =>
      ctx.complete((GatewayTimeout, "Request timeout"))
    case e: Any =>
      log.error(s"Unhandled message: $e")
      ctx.complete(BadRequest)
  }

  override val supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy() {
      case e => {
        ctx.complete((InternalServerError, e.getMessage))
        Stop
      }
    }
}

object PerRequest {

  case class WithActorRef(ctx: RequestContext, target: ActorRef, message: RestMessage)
    extends PerRequest

  case class WithProps(ctx: RequestContext, props: Props, message: RestMessage) extends PerRequest {
    lazy val target: ActorRef = context.actorOf(props)
  }

  final class RequestContextWrapper(ctx: RequestContext, promise: Promise[RouteResult]) {
    private implicit val ec = ctx.executionContext

    def complete(response: ToResponseMarshallable): Unit = ctx.complete(response).onComplete(promise.complete)

    def fail(error: Throwable): Unit = ctx.fail(error).onComplete(promise.complete)
  }

  def asyncComplete(inner: RequestContextWrapper => Unit): Route = { ctx: RequestContext =>
    val p = Promise[RouteResult]()
    inner(new RequestContextWrapper(ctx, p))
    p.future
  }

}

trait PerRequestCreator {

  import PerRequest._

  def context: ActorRefFactory

  def perRequest(ctx: RequestContext, target: ActorRef, message: RestMessage): ActorRef =
    context.actorOf(Props(WithActorRef(ctx, target, message)))

  def perRequest(ctx: RequestContext, props: Props, message: RestMessage): ActorRef =
    context.actorOf(Props(WithProps(ctx, props, message)))
}
