package api

import akka.actor.ActorRef
import akka.util.Timeout
import core.ChronosActor
import core.model.results.BoxPlotResult
import spray.http._
import spray.routing.Route
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// this trait defines our service behavior independently from the service actor
class JobService(chronos: ActorRef)(implicit executionContext: ExecutionContext) extends JobServiceDoc with DefaultJsonFormats {

  val routes: Route = initJob

  import akka.pattern.ask
  import JobDto._
  import ResultDto._
  import BoxPlotResult._
  import ChronosActor._

  implicit val seqBoxPlotResFormat = seqFormat[BoxPlotResult]

  implicit object EitherErrorSelector extends ErrorSelector[ErrorResponse.type] {
    def apply(v: ErrorResponse.type): StatusCode = StatusCodes.BadRequest
  }

  override def initJob: Route = path("job") {
    put {
      handleWith { job: JobDto =>
        println (s"Received job $job")
        implicit val timeout: Timeout = Timeout(5.minutes)
        (chronos ? Start(job)).mapTo[Either[ErrorResponse, Results]]
      }
    }
  }
}
