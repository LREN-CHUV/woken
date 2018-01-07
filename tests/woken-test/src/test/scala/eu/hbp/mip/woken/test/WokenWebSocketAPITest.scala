/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.test

import java.util.concurrent.TimeUnit

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._

class WokenWebSocketAPITest
  extends FlatSpec
    with Matchers
    with Queries
    with ScalaFutures with BeforeAndAfterAll {

  val configuration = ConfigFactory.load()
  implicit val system = ActorSystem("WebSocketAPITest")
  implicit val materializer = ActorMaterializer()

  implicit val executionContext = system.dispatcher

  override def afterAll = {
    system.terminate().onComplete { result =>
      println("Actor system shutdown: " + result)
    }
  }


  "Woken" should "respond to a query for the list of methods using websocket" in {

    executeQuery(None, None, "ws://woken:8087/mining/methods")

  }

  "Woken" should "respond to a mining query using websocket" in {

    executeQuery(Some("/knn_data_mining_query.json"),
      Some("/knn_data_mining.json"),
      "ws://woken:8087/mining/job")

  }

  "Woken" should "respond to an experiment query using websocket" in {

    executeQuery(Some("/knn_experiment_query.json"),
      Some("/knn_experiment.json"),
      "ws://Woken:8087/mining/experiment")

  }

  private def assertionAsSink(
                               expectedResponse: Option[String]): Sink[Message, Future[Done]] = {
    Sink.foreach {
      case message: TextMessage.Strict =>
        message.text.isEmpty shouldBe false
        println(message.text)
        if (expectedResponse.isDefined) {
          val json = message.getStrictText.parseJson
          val expected = loadJson(expectedResponse.get)

          assertResult(approximate(expected))(approximate(json))
        }
      case err =>
        fail("Unexpected response received: " + err.toString)
    }
  }

  private def executeQuery(probeData: Option[String],
                           expectedResult: Option[String],
                           endpointUrl: String) = {
    val probeSource: Source[Message, NotUsed] = probeData match {
      case Some(probe) =>
        val source = scala.io.Source.fromURL(getClass.getResource(probe))
        val query = source.mkString
        Source.single(TextMessage.apply(query))
      case None =>
        Source.single(TextMessage.apply(""))
    }

    val assertSink: Sink[Message, Future[Done]] = assertionAsSink(
      expectedResult)

    val flow: Flow[Message, Message, Future[Done]] =
      Flow
        .fromSinkAndSourceMat(assertSink, probeSource)(Keep.left)
        .keepAlive(FiniteDuration(1, TimeUnit.SECONDS),
          () => TextMessage.apply("heart beat"))

    val (upgradeResponse, closed) =
      Http().singleWebSocketRequest(WebSocketRequest(endpointUrl), flow)

    val connected = upgradeResponse.map { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Done
      } else {
        throw new RuntimeException(
          s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(println)

    whenReady(closed, timeout = Timeout(Span(5, Minutes))) { result =>
      println(result)
    }

  }

}
