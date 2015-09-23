package api

import akka.actor._
import akka.actor.SupervisorStrategy.Stop
import org.json4s.DefaultFormats
import spray.http.StatusCodes._
import spray.httpx.Json4sSupport
import spray.routing.RequestContext
import akka.actor.OneForOneStrategy
import scala.concurrent.duration._
import spray.http.StatusCode

import core._

trait PerRequest extends Actor with ActorLogging with Json4sSupport {

  import context._
  val json4sFormats = DefaultFormats

  def r: RequestContext
  def target: ActorRef
  def message: RestMessage

  setReceiveTimeout(180.seconds) // TODO: make configurable, align with spray.can.timeout
  target ! message

  def receive = {
    case res: RestMessage => complete(OK, res)
    case v: Validation    => complete(BadRequest, v)
    case v: Error         => complete(BadRequest, v)
    case ReceiveTimeout   => complete(GatewayTimeout, Error("Request timeout"))
    case e                => log.error("Unhandled message: $e")
  }

  def complete[T <: AnyRef](status: StatusCode, obj: T) = {
    r.complete((status, obj))
    stop(self)
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        complete(InternalServerError, Error(e.getMessage))
        Stop
      }
    }
}

object PerRequest {
  case class WithActorRef(r: RequestContext, target: ActorRef, message: RestMessage) extends PerRequest

  case class WithProps(r: RequestContext, props: Props, message: RestMessage) extends PerRequest {
    lazy val target = context.actorOf(props)
  }
}

trait PerRequestCreator {

  import PerRequest._

  def context: ActorRefFactory

  def perRequest(r: RequestContext, target: ActorRef, message: RestMessage) =
    context.actorOf(Props(new WithActorRef(r, target, message)))

  def perRequest(r: RequestContext, props: Props, message: RestMessage) =
    context.actorOf(Props(new WithProps(r, props, message)))
}
