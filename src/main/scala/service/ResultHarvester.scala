package service

import akka.actor.Actor

class ResultHarvester extends Actor {

  case class Complete()

  override def receive = {
    case Complete =>
  }
}
