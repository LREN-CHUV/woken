package eu.hbp.mip.woken.api

import akka.actor.{ActorRef, ActorSystem, Props}
import spray.http._
import spray.routing.Route
import spray.json._
import MediaTypes._

import eu.hbp.mip.woken.core.{CoordinatorActor, ExperimentActor, JobResults, RestMessage}
import eu.hbp.mip.woken.dao.{JobResultsDAL, LdsmDAL}

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
