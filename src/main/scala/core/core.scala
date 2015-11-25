package core

import akka.actor.{ActorRef, Props, ActorSystem}
import core.clients.ChronosService

/**
 * Core is type containing the ``system: ActorSystem`` member. This enables us to use it in our
 * apps as well as in our tests.
 */
trait Core {

  protected implicit def system: ActorSystem

}

/**
 * This trait contains the actors that make up our application; it can be mixed in with
 * ``BootedCore`` for running code or ``TestKit`` for unit and integration tests.
 */
trait CoreActors {
  this: Core =>

  val chronosHttp: ActorRef = system.actorOf(Props[ChronosService], "http.chronos")

}
