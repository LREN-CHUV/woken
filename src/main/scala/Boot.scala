import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import router.ApiRouterActor
import service.JobService
import spray.can.Http
import utils.Config._

import scala.concurrent.duration._


object Boot extends App {

  // we need an ActorSystem to host our application in
  implicit val system = ActorSystem(app.systemName)

  // create and start our service actor
  val jobActor: ActorRef = system.actorOf(Props(classOf[ApiRouterActor], JobService), app.jobServiceName)

  implicit val timeout = Timeout(5.seconds)
  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http) ? Http.Bind(jobActor, interface = app.interface, port = app.port)

}
