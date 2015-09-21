package api

import akka.actor.ActorRef
import akka.util.Timeout
import spray.http.MediaTypes._
import spray.httpx.SprayJsonSupport._
import spray.routing.Route
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

// this trait defines our service behavior independently from the service actor
class JobService(chronos: ActorRef, implicit val dispatcher: ExecutionContextExecutor) extends JobServiceDoc with DefaultJsonFormats {

  val routes: Route = initJob

  import akka.pattern.ask
  override def initJob: Route = path("job") {
    put {
      entity(as[JobDto]) { job =>
        respondWithMediaType(`application/json`) {
          handleWith { _:Any =>
            implicit val timeout: Timeout = Timeout(5.minutes)
            (chronos ? job).mapTo[Either[String, ResultDto]]
          }
        }
      }
    }
  }
}
