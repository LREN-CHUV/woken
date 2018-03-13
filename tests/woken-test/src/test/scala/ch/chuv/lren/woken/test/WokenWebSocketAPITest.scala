/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.test

import java.util.concurrent.TimeUnit

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.immutable.Seq

class WokenWebSocketAPITest
    extends FlatSpec
    with Matchers
    with Queries
    with ScalaFutures
    with BeforeAndAfterAll {

  val configuration: Config = ConfigFactory.load()
  implicit val system: ActorSystem = ActorSystem("WebSocketAPITest")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  implicit val executionContext: ExecutionContext = system.dispatcher

  val remoteHostName: String = configuration.getString("clustering.seed-ip")

  override def afterAll: Unit = {
    system.terminate().onComplete { result =>
      println("Actor system shutdown: " + result)
    }
  }

  "Woken" should "respond to a query for the list of methods using websocket" in {

    executeQuery(None, None, s"ws://$remoteHostName:8087/mining/methods")

  }

  "Woken" should "respond to a query for the list of datasets using websocket" in {

    executeQuery(None, None, s"ws://$remoteHostName:8087/datasets")

  }

  "Woken" should "respond to a mining query using websocket" in {

    executeQuery(Some("/knn_data_mining_query.json"),
                 Some("/knn_data_mining.json"),
                 s"ws://$remoteHostName:8087/mining/job")

  }

  "Woken" should "respond to a mining query with empty algorithm using websocket" in {

    executeQuery(Some("/knn_data_mining_empty_alg_query.json"),
                 Some("/knn_data_mining_empty_alg.json"),
                 s"ws://$remoteHostName:8087/mining/job")

  }

  "Woken" should "respond to an experiment query using websocket" in {

    executeQuery(Some("/knn_experiment_query.json"),
                 Some("/knn_experiment.json"),
                 s"ws://$remoteHostName:8087/mining/experiment")

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
                           endpointUrl: String): Unit = {
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
      Http().singleWebSocketRequest(
        WebSocketRequest(
          endpointUrl,
          extraHeaders =
            Seq(Authorization(BasicHttpCredentials("admin", "WoKeN")))),
        flow
      )

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
