package router

import service.JobService
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing.{ HttpService, Route }
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

// this trait defines our service behavior independently from the service actor
trait JobRouter extends HttpService with JobRouterDoc {

  val jobService: JobService

  val jobOperations: Route = initJob

  override def initJob: Route = path("job") {
    put {
      entity(as[JobDto]) { job =>
        respondWithMediaType(`application/json`) {
          onComplete(jobService.add(job)) {
            case Success(Some(newJob)) => complete(Created, newJob)
            case Success(None) => complete(NotAcceptable, "Invalid user")
            case Failure(ex) => complete(InternalServerError, s"An error occurred: ${ex.getMessage}")
          }
        }
      }
    }
  }
}
