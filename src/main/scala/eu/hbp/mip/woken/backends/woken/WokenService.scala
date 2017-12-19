package eu.hbp.mip.woken.backends.woken

import akka.actor.{Actor, ActorLogging}
import com.github.levkhomich.akka.tracing.ActorTracing

class WokenService extends Actor with ActorLogging
  with ActorTracing {
  override def receive: Receive = ???
}
