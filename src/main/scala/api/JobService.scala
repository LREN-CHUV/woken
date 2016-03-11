package api

import akka.actor.{ActorSystem, ActorRef}
import core.{JobResults, RestMessage, CoordinatorActor}
import dao.{LdsmDAL, JobResultsDAL}
import spray.http._
import spray.routing.Route

// this trait defines our service behavior independently from the service actor
class JobService(val chronosService: ActorRef,
                 val resultDatabase: JobResultsDAL,
                 val federationDatabase: Option[JobResultsDAL],
                 val ldsmDatabase: LdsmDAL)(implicit system: ActorSystem) extends JobServiceDoc with PerRequestCreator with DefaultJsonFormats {

  override def context = system
  val routes: Route = initJob ~ mining

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

  override def mining: Route = path("mining") {
    import FunctionsInOut._

    post {
      entity(as[Query]) {
        case Query(variables, covariables, groups, _, algorithm) if algorithm == "" || algorithm == "data" => {
          ctx => ctx.complete(ldsmDatabase.queryData({ variables ++ covariables ++ groups }.distinct.map(_.code)))
        }
        case query: Query => {
          val job = query2job(query)
          chronosJob(RequestProtocol) {
            Start(job)
          }
        }
      }
    }
  }

  def chronosJob(jobResultsFactory: JobResults.Factory = JobResults.defaultFactory)(message : RestMessage): Route =
    ctx => perRequest(ctx, CoordinatorActor.props(chronosService, resultDatabase, federationDatabase, jobResultsFactory), message)

}
