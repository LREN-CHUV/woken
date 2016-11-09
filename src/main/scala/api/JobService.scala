package api

import akka.actor.{ActorRef, ActorSystem, Props}
import core.{CoordinatorActor, ExperimentActor, JobResults, RestMessage}
import dao.{JobResultsDAL, LdsmDAL}
import spray.http._
import spray.routing.Route
import spray.json._
import MediaTypes._


// this trait defines our service behavior independently from the service actor
class JobService(val chronosService: ActorRef,
                 val resultDatabase: JobResultsDAL,
                 val federationDatabase: Option[JobResultsDAL],
                 val ldsmDatabase: LdsmDAL)(implicit system: ActorSystem) extends JobServiceDoc with PerRequestCreator with DefaultJsonFormats {

  override def context = system
  val routes: Route = initJob

  import JobDto._
  import CoordinatorActor._
  import ApiJsonSupport._

  implicit object EitherErrorSelector extends ErrorSelector[ErrorResponse.type] {
    def apply(v: ErrorResponse.type): StatusCode = StatusCodes.BadRequest
  }

  override def initJob: Route = path("job") {
    post {
      entity(as[JobDto]) { job =>
        chronosJob() {
          Start(job)
        }
      }
    }
  }

  def chronosJob(jobResultsFactory: JobResults.Factory = JobResults.defaultFactory)(message : RestMessage): Route =
    ctx => perRequest(ctx, CoordinatorActor.props(chronosService, resultDatabase, federationDatabase, jobResultsFactory), message)
}
