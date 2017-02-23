package eu.hbp.mip.woken.test

import java.time.OffsetDateTime

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Success, Failure}

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, ExtendedActorSystem, Extension, ExtensionKey, Props, RootActorPath}
import akka.testkit.TestActorRef
import akka.pattern.ask
import akka.util.Timeout
import scala.util.Success
import org.scalatest.{FlatSpec, Matchers}

import eu.hbp.mip.messages.external._


// TODO This code will be common to all Akka service in containers -> put it as a small woken common lib!
class RemotePathExtensionImpl(system: ExtendedActorSystem) extends Extension {
  def getPath(actor: Actor) = {
    actor.self.path.toStringWithAddress(system.provider.getDefaultAddress)
  }
}
object RemotePathExtension extends ExtensionKey[RemotePathExtensionImpl]

class RemoteAddressExtensionImpl(system: ExtendedActorSystem) extends Extension {
  def getAddress() = {
    system.provider.getDefaultAddress
  }
}
object RemoteAddressExtension extends ExtensionKey[RemoteAddressExtensionImpl]

class AkkaAPITest extends FlatSpec with Matchers {

  implicit val timeout = Timeout(60 seconds)
  val system = ActorSystem("woken-test")

  // Test methods query
  {
    val ref = system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")
    val future = ref ? MethodsQuery()

    val result =
      try {
        Await.result(future, timeout.duration)
      } catch {
        case te: java.util.concurrent.TimeoutException => this.fail("Timeout!")
        case e: Exception => this.fail()
      }

    result should matchPattern { case r: Methods => }
  }

  // Test mining query
  {
    val ref = system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")
    val future = ref ? MiningQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_math_course1")),
      Seq.empty[VariableId],
      Seq.empty[Filter],
      Algorithm("knn", "K-nearest neighbors with k=5", Map[String, String]("k" -> "5"))
    )

    val result =
      try {
        Await.result(future, timeout.duration)
      } catch {
        case te: java.util.concurrent.TimeoutException => this.fail("Timeout!")
        case e: Exception => this.fail()
      }

    result should matchPattern { case r: QueryResult => }
  }

  // Test experience query
  {
    val ref = system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")
    val future = ref ? ExperimentQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_test1"), VariableId("college_math")),
      Seq.empty[VariableId],
      Seq.empty[Filter],
      List(Algorithm("knn", "K-nearest neighbors with k=5", Map[String, String]("k" -> "5"))),
      List(Validation("kfold", "kfold", Map("k" -> "2")))
    )

    val result =
      try {
        Await.result(future, timeout.duration)
      } catch {
        case te: java.util.concurrent.TimeoutException => this.fail("Timeout!")
        case e: Exception => this.fail()
      }

    result should matchPattern { case r: QueryResult => }
  }
}
