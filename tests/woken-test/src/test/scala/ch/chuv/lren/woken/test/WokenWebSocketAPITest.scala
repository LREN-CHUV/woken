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
import ch.chuv.lren.woken.monitoring.KamonSupport
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import kamon.Kamon
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.collection.immutable.Seq

class WokenWebSocketAPITest
    extends WordSpec
    with Matchers
    with Queries
    with ScalaFutures
    with BeforeAndAfterAll
    with LazyLogging {

  val config: Config =
    ConfigFactory
      .parseString("""
          |akka {
          |  actor.provider = local
          |}
        """.stripMargin)
      .withFallback(ConfigFactory.load())
      .withFallback(ConfigFactory.parseResourcesAnySyntax("kamon.conf"))
      .resolve()

  implicit val system: ActorSystem = ActorSystem("WebSocketAPITest", config)
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  implicit val executionContext: ExecutionContext = system.dispatcher

  KamonSupport.startReporters(config)

  val remoteHostName: String = config.getString("clustering.seed-ip")
  val distributed: Boolean = config.getBoolean("test.distributed")

  override def afterAll: Unit = {
    Kamon.stopAllReporters()
    system.terminate().onComplete { result =>
      logger.debug(s"Actor system shutdown: $result")
    }
  }

  "Woken" should {

    "respond to a query for the list of algorithms using websocket" in {
      executeQuery(None,
                   Some("/responses/list_algorithms.json"),
                   s"ws://$remoteHostName:8087/mining/algorithms")
    }

    "respond to a query for the list of datasets using websocket" in {
      val reference =
        if (distributed) "/responses/list_datasets_distributed.json"
        else "/responses/list_datasets.json"
      executeQuery(None,
                   Some(reference),
                   s"ws://$remoteHostName:8087/metadata/datasets")
    }

    "respond to a mining query using websocket" in {
      executeQuery(Some("/responses/knn_data_mining_query.json"),
                   Some("/responses/knn_data_mining.json"),
                   s"ws://$remoteHostName:8087/mining/job")

    }

    "respond to an experiment query using websocket" in {
      executeQuery(Some("/responses/knn_experiment_query.json"),
                   Some("/responses/knn_experiment.json"),
                   s"ws://$remoteHostName:8087/mining/experiment")
    }

  }

  private def assertionAsSink(
      expectedResponse: Option[String]): Sink[Message, Future[Done]] = {
    Sink.foreach {
      case message: TextMessage.Strict =>
        message.text.isEmpty shouldBe false
        if (expectedResponse.isDefined) {
          val json = message.getStrictText.parseJson
          val expected = loadJson(expectedResponse.get)

          assertResult(approximate(expected))(approximate(json))
        } else {
          println("Received, please add a check for this result: ")
          val json = message.getStrictText.parseJson
          println(json.prettyPrint)
        }
      case err =>
        fail("Unexpected response received: " + err.toString)
    }
  }

  private def executeQuery(probeData: Option[String],
                           expectedResult: Option[String],
                           endpointUrl: String): Unit = {

    val span =
      Kamon.buildSpan(endpointUrl.split("/").takeRight(2).mkString("-")).start()

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
      Kamon.withSpan(span) {
        Http().singleWebSocketRequest(
          WebSocketRequest(
            endpointUrl,
            extraHeaders =
              Seq(Authorization(BasicHttpCredentials("admin", "WoKeN")))),
          flow
        )
      }

    val connected = upgradeResponse.map { upgrade =>
      if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
        Done
      } else {
        throw new RuntimeException(
          s"Connection failed: ${upgrade.response.status}")
      }
    }

    connected.onComplete(t => logger.debug(t.toString))

    whenReady(closed, timeout = Timeout(Span(5, Minutes))) { result =>
      span.finish()
      logger.debug(result.toString)
    }

  }

}
