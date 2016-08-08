package eu.hbp.mip.validation

import akka.actor.{Actor, ActorLogging, ActorSystem, Address, ExtendedActorSystem, Extension, ExtensionKey, Props}
import akka.cluster.Cluster
import com.opendatagroup.hadrian.datatype.{AvroDouble, AvroString}
import com.opendatagroup.hadrian.jvmcompiler.PFAEngine

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

class ValidationActor extends Actor with ActorLogging {

  def receive = {
    case ("Work", fold: String, model: String, data: List[String]) ⇒
      log.info("Received validation work!")
      // Reconstruct model using hadrian and validate over the provided data
      val replyTo = sender()
      try {
        // Run the model on data
        val engine = PFAEngine.fromJson(model).head
        val variableType: String = engine.outputType match {
          case v: AvroString => "nominal"
          case v: AvroDouble => "real"
          case _ => "invalid"
        }
        val inputData = engine.jsonInputIterator[AnyRef](data.iterator)
        val outputData : List[String] = inputData.map(x => engine.jsonOutput(engine.action(x))).toList
        log.info("Validation work for " + fold + " done!")

        replyTo ! ("Done", fold, variableType, outputData)
      } catch {
        // TODO Too generic!
        case e: Exception => {
          log.error("Error in validation work: " + e)
          replyTo ! ("Error", e.toString())
        }
      }
    case _ => log.error("Validation work not recognized!")
  }
}

object Main extends App {

  val system = ActorSystem("woken")

  // TODO Read the address from env vars
  //Cluster(system).join(Address("akka.tcp", "woken", "127.0.0.1", 8088))
  lazy val cluster = Cluster(system)

  // Start the local validation actor
  val validationActor = system.actorOf(Props[ValidationActor], name = "validation")
}
