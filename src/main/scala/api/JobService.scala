package api

import akka.actor.{ActorSystem, ActorRef}
import core.{RestMessage, CoordinatorActor}
import dao.DAL
import spray.http._
import spray.routing.Route

// this trait defines our service behavior independently from the service actor
class JobService(val chronosService: ActorRef, val resultDatabase: DAL, val federationDatabase: Option[DAL])(implicit system: ActorSystem) extends JobServiceDoc with PerRequestCreator with DefaultJsonFormats {

  override def context = system
  val routes: Route = initJob

  import JobDto._
  import CoordinatorActor._

  implicit object EitherErrorSelector extends ErrorSelector[ErrorResponse.type] {
    def apply(v: ErrorResponse.type): StatusCode = StatusCodes.BadRequest
  }

  override def initJob: Route = path("job") {
    post {
      entity(as[JobDto]) { job =>
        chronosJob {
          Start(job)
        }
      }
    }
  }

  def chronosJob(message : RestMessage): Route =
    ctx => perRequest(ctx, CoordinatorActor.props(chronosService, resultDatabase, federationDatabase), message)

}
