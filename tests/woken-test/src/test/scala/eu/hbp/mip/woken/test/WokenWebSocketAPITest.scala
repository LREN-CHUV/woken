package eu.hbp.mip.woken.test

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.typesafe.config.ConfigFactory
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future

class WokenWebSocketAPITest extends FlatSpec with Matchers {

  val configuration = ConfigFactory.load()
  implicit val system = ActorSystem("WebSocketAPITest")
  implicit val materializer = ActorMaterializer()


  "Woken" should "respond to a query for the list of methods" in {

    val asssertSink: Sink[Message, Future[Done]] =
      Sink.foreach {
        case message: TextMessage.Strict =>
          println(message.text)
          message.text.isEmpty shouldBe false
      }

    val listMethodsSource: Source[Message, NotUsed] = Source.single(TextMessage("messages"))

    val flow: Flow[Message, Message, Future[Done]] =
      Flow.fromSinkAndSourceMat(asssertSink, listMethodsSource)(Keep.left)

    val (upgradeResponse, closed) =
      Http().singleWebSocketRequest(WebSocketRequest("ws://localhost:8080/ws/mining/methods"), flow)

    val connected = upgradeResponse.map { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Done
      } else {
        throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(println)
    closed.foreach(_ => println("closed"))

  }

}
