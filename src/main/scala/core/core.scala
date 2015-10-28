package core

import akka.actor.{Props, ActorSystem}
import core.clients.{DatabaseService, ChronosService}
import dao.BoxPlotResultDao

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

  val chronosHttp = system.actorOf(Props[ChronosService], "http.chronos")
  val databaseService = system.actorOf(Props(new DatabaseService(BoxPlotResultDao)), "db")

}