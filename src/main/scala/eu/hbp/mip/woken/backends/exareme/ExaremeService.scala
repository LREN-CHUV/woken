package eu.hbp.mip.woken.backends.exareme

import akka.actor.{Actor, ActorLogging}
import com.github.levkhomich.akka.tracing.ActorTracing

class ExaremeService extends Actor with ActorLogging
  with ActorTracing {
  override def receive: Receive = ???
}
