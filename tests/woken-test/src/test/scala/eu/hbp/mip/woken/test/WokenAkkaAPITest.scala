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

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.client.{ClusterClient, ClusterClientSettings}
import akka.pattern.ask
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import eu.hbp.mip.woken.messages.external._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.TryValues._
import org.scalatest.tagobjects.Slow
import spray.json._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps
import scala.util.Try

class WokenAkkaAPITest extends FlatSpec with Matchers {

  implicit val timeout: Timeout = Timeout(200 seconds)
  val configuration = ConfigFactory.load()
  val system = ActorSystem(configuration.getString("clustering.cluster.name"),
                           configuration)
  implicit val ec: ExecutionContext = system.dispatcher

  val client: ActorRef =
    system.actorOf(ClusterClient.props(ClusterClientSettings(system)), "client")
  val entryPoint = "/user/entrypoint"

  // Test methods query
  "Woken" should "respond to a query for the list of methods" in {

    val start = System.currentTimeMillis()
    val future = client ? ClusterClient.Send(entryPoint,
                                             MethodsQuery,
                                             localAffinity = true)
    val result = waitFor[MethodsResponse](future)
    val end = System.currentTimeMillis()

    println(
      "List of methods query complete in " + Duration(end - start,
                                                      TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.methods shouldNot have size 0
  }

  // Test mining query
  "Woken" should "respond to a data mining query" in {

    val start = System.currentTimeMillis()
    val query = MiningQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_math_course1")),
      Nil,
      "",
      AlgorithmSpec("knn", List(CodeValue("k", "5")))
    )

    val future = client ? ClusterClient.Send(entryPoint,
                                             query,
                                             localAffinity = true)
    val result = waitFor[QueryResult](future)
    val end = System.currentTimeMillis()

    println(
      "Data mining query complete in " + Duration(end - start,
                                                  TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.data should not be empty

    val json = result.success.value.data.get.parseJson
    val expected = loadJson("/knn_data_mining.json")

    assertResult(approximate(expected))(approximate(json))
  }

  "Woken" should "respond to a data mining query with visualisation" in {

    val start = System.currentTimeMillis()
    val query = MiningQuery(
      List(VariableId("cognitive_task2")),
      List("score_math_course1", "score_math_course2").map(VariableId),
      Nil,
      "",
      AlgorithmSpec("histograms", Nil)
    )

    val future = client ? ClusterClient.Send(entryPoint,
                                             query,
                                             localAffinity = true)
    val result = waitFor[QueryResult](future)
    val end = System.currentTimeMillis()

    println(
      "Data mining query with visualisation complete in " + Duration(
        end - start,
        TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.data should not be empty

    val json = result.success.value.data.get.parseJson
    val expected = loadJson("/histograms.json")

    assertResult(approximate(expected))(approximate(json))
  }

  // Test experiment query
  "Woken" should "respond to an experiment query" in {

    val start = System.currentTimeMillis()
    val query = experimentQuery("knn", List(CodeValue("k", "5")))
    val future = client ? ClusterClient.Send(entryPoint,
                                             query,
                                             localAffinity = true)
    val result = waitFor[QueryResult](future)
    val end = System.currentTimeMillis()

    println(
      "Experiment query complete in " + Duration(end - start,
                                                 TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    val data = result.success.value.data

    data should not be empty

    val json = data.get.parseJson
    val expected = loadJson("/knn_experiment.json")

    assertResult(approximate(expected))(approximate(json))
  }

  // Test resiliency
  "Woken" should "recover from multiple failed experiments" taggedAs Slow in {

    // TODO: add no_results, never_end
    val failures = List("training_fails",
                        "invalid_json",
                        "invalid_pfa_syntax",
                        "invalid_pfa_semantics")

    val queries = failures.map(failure =>
      experimentQuery("chaos", List(CodeValue("failure", failure))))

    val futures = queries.map(query =>
      client ? ClusterClient.Send(entryPoint, query, localAffinity = true))

    futures.foreach { f =>
      println("Waiting for result from chaos algorithm...")
      val result = waitFor[QueryResult](f)
      if (result.isFailure) {
        println(s"Chaos algorithm failed with ${result.failed.get}")
      } else {
        println(s"Chaos algorithm returned ${result.success.value}")
      }
    }

    val knnQuery = experimentQuery("knn", List(CodeValue("k", "5")))
    val successfulFuture = client ? ClusterClient.Send(entryPoint,
                                                       knnQuery,
                                                       localAffinity = true)
    val result = waitFor[QueryResult](successfulFuture)

    if (!result.isSuccess) {
      println(result)
    }

    val data = result.success.value.data

    data should not be empty

    val json = data.get.parseJson
    val expected = loadJson("/knn_experiment.json")

    assertResult(approximate(expected))(approximate(json))

  }

  private def experimentQuery(algorithm: String, parameters: List[CodeValue]) =
    ExperimentQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_test1"), VariableId("college_math")),
      Nil,
      "",
      List(AlgorithmSpec(algorithm, parameters)),
      List(ValidationSpec("kfold", List(CodeValue("k", "2"))))
    )

  private def waitFor[T](future: Future[Any])(
      implicit timeout: Timeout): Try[T] = {
    Try {
      Await.result(future, timeout.duration).asInstanceOf[T]
    }
  }

  private def loadJson(path: String): JsValue = {
    val source = Source.fromURL(getClass.getResource(path))
    source.mkString.parseJson
  }

  private def approximate(json: JsValue): String = {
    val sb = new java.lang.StringBuilder()
    new ApproximatePrinter().print(json, sb)
    sb.toString
  }

  class ApproximatePrinter extends SortedPrinter {

    override protected def printObject(members: Map[String, JsValue],
                                       sb: java.lang.StringBuilder,
                                       indent: Int): Unit = {
      val filteredMembers = members.map {
        case ("jobId", _)     => "jobId" -> JsString("*")
        case ("timestamp", _) => "timestamp" -> JsNumber(0.0)
        case (k, v)           => k -> v
      }
      super.printObject(filteredMembers, sb, indent)
    }

    override protected def printLeaf(j: JsValue,
                                     sb: java.lang.StringBuilder): Unit =
      j match {
        case JsNull      => sb.append("null")
        case JsTrue      => sb.append("true")
        case JsFalse     => sb.append("false")
        case JsNumber(x) => sb.append(f"$x%1.5f")
        case JsString(x) => printString(x, sb)
        case _           => throw new IllegalStateException
      }

  }
}
