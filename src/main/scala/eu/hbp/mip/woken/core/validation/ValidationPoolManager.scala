package eu.hbp.mip.woken.core.validation

import akka.actor.{Actor, ActorLogging, ActorPath, RootActorPath}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._

object ValidationPoolManager {
  val validationPool = scala.collection.mutable.Set[ActorPath]()
}

class ValidationPoolManager extends Actor with ActorLogging{

  // Upon start say "Hi" to Woken
  /* override def preStart(): Unit = {
     val remotePath = RemotePathExtension(context.system).getPath(this)
     wokenActor ! ("Hi!", remotePath)
   }*/

  val cluster = Cluster(context.system)

  // subscribe to cluster changes, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case MemberUp(member) =>
      if (member.hasRole("validation")) {
        log.info("New validation node in pool: " + member)
        ValidationPoolManager.validationPool += RootActorPath(member.address) / "user" / "validation"
      }
    case UnreachableMember(member) => Unit
    case MemberRemoved(member, previousStatus) =>
      if (member.hasRole("validation"))
        ValidationPoolManager.validationPool -= RootActorPath(member.address) / "user" / "validation"
    case _: MemberEvent => // ignore
  }
}