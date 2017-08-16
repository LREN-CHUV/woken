package eu.hbp.mip.woken.web

import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, ExtendedActorSystem, Extension, ExtensionKey, Props}
import akka.io.IO
import akka.util.Timeout
import akka.cluster.Cluster
import spray.can.Http
import eu.hbp.mip.woken.api.{Api, RoutedHttpService}
import eu.hbp.mip.woken.core.Core
import eu.hbp.mip.woken.config.Config.app
import eu.hbp.mip.woken.core.validation.ValidationPoolManager

class RemoteAddressExtensionImpl(system: ExtendedActorSystem) extends Extension {
  def getAddress() = {
    system.provider.getDefaultAddress
  }
}
object RemoteAddressExtension extends ExtensionKey[RemoteAddressExtensionImpl]

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

  //Cluster(system).join(RemoteAddressExtension(system).getAddress())
  lazy val cluster = Cluster(system)

  /**
   * Create and start our service actor
   */
  val rootService: ActorRef = system.actorOf(Props(classOf[RoutedHttpService], routes ~ staticResources), app.jobServiceName)

  /**
    * Create and start actor that acts as akka entry-point
    */
  val mainRouter: ActorRef = system.actorOf(Props(classOf[eu.hbp.mip.woken.api.MasterRouter], this), name = "entrypoint")

  /**
   * Create and start actor responsible to register validation node
   */
  val validationRegisterActor: ActorRef = system.actorOf(Props[ValidationPoolManager])

  implicit val timeout = Timeout(5.seconds)

  // start a new HTTP server on port 8080 with our service actor as the handler
  IO(Http)(system) ! Http.Bind(rootService, interface = app.interface, port = app.port)

  /**
   * Ensure that the constructed ActorSystem is shut down when the JVM shuts down
   */
  sys.addShutdownHook(system.shutdown())

}

