package eu.hbp.mip.woken.core

import akka.actor.{Actor, ActorLogging}
import com.github.levkhomich.akka.tracing.ActorTracing

class JobClientService extends Actor with ActorLogging
  with ActorTracing {
  override def receive: Receive = ???
}
