package web

import akka.actor.{Props, ActorRefFactory, ActorSystem}
import akka.io.IO
import akka.util.Timeout
import api.{RoutedHttpService, Api}
import core.Core
import spray.can.Http
import config.Config.app
import scala.concurrent.duration._

/**
 * This trait implements ``Core`` by starting the required ``ActorSystem`` and registering the
 * termination handler to stop the system when the JVM exits.
 */
trait BootedCore extends Core with Api with StaticResources {
  /**
   * Construct the ActorSystem we will use in our application
   */
  lazy val system: ActorSystem = ActorSystem(app.systemName)
  lazy val actorRefFactory: ActorRefFactory = system

  /**
   * Create and start our service actor
   */
  val rootService = system.actorOf(Props(classOf[RoutedHttpService], routes ~ staticResources ), app.jobServiceName)

  implicit val timeout = Timeout(5.seconds)

  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http)(system) ! Http.Bind(rootService, interface = app.interface, port = app.port)

  /**
   * Ensure that the constructed ActorSystem is shut down when the JVM shuts down
   */
  sys.addShutdownHook(system.shutdown())

}


