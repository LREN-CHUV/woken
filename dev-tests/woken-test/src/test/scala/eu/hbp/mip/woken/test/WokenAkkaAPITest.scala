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

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import eu.hbp.mip.woken.messages.external._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.TryValues._
import spray.json._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.Source
import scala.language.postfixOps
import scala.util.Try

class WokenAkkaAPITest extends FlatSpec with Matchers {

  implicit val timeout: Timeout = Timeout(200 seconds)
  val system = ActorSystem("woken-test")

  // Test methods query
  "Woken" should "respond to a query for the list of methods" in {
    val api =
      system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")
    val start = System.currentTimeMillis()

    val future = api ? MethodsQuery()

    val result = waitFor[Methods](future)

    val end = System.currentTimeMillis()

    println("Complete in " + Duration(end - start, TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.methods shouldNot have size 0
  }

  // Test mining query
  "Woken" should "respond to a data mining query" in {
    val api =
      system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")
    val start = System.currentTimeMillis()

    val future = api ? MiningQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_math_course1")),
      Nil,
      "",
      Algorithm("knn",
                "K-nearest neighbors with k=5",
                Map[String, String]("k" -> "5"))
    )

    val result = waitFor[QueryResult](future)

    val end = System.currentTimeMillis()

    println("Complete in " + Duration(end - start, TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    result.success.value.data should not be empty

    val json = result.success.value.data.get.parseJson
    val expected = loadJson("/knn_data_mining.json")

    assertResult(approximate(expected))(approximate(json))
  }

  // Test experiment query
  "Woken" should "respond to an experiment query" in {
    val api =
      system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")
    val start = System.currentTimeMillis()

    val future = api ? experimentQuery("knn",
                                       "K-nearest neighbors with k=5",
                                       Map("k" -> "5"))

    val result = waitFor[QueryResult](future)

    val end = System.currentTimeMillis()

    println("Complete in " + Duration(end - start, TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      println(result)
    }

    val data = result.success.value.data

    data should not be empty

    val json =  data.get.parseJson
    val expected = loadJson("/knn_experiment.json")

    assertResult(approximate(expected))(approximate(json))
  }

  // Test resiliency
  "Woken" should "recover from multiple failed experiments" in {
    val api =
      system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")

    // TODO: add no_results, never_end
    val failures = List("training_fails",
                        "invalid_json",
                        "invalid_pfa_syntax",
                        "invalid_pfa_semantics")

    val futures = failures.map(failure =>
      api ? experimentQuery("chaos", "Failure", Map("failure" -> failure)))

    futures.foreach { f =>
      println("Waiting for result...")
      val result = waitFor[QueryResult](f)
      println(s"Chaos algorithm returned ${result.success.value}")
    }

    val successfulFuture = api ? experimentQuery("knn",
                                                 "K-nearest neighbors with k=5",
                                                 Map("k" -> "5"))

    val result = waitFor[QueryResult](successfulFuture)

    if (!result.isSuccess) {
      println(result)
    }

    val data = result.success.value.data

    data should not be empty

    val json = data.get.parseJson

    assertResult(loadJson("/knn_experiment.json"))(json)

  }

  private def experimentQuery(algorithm: String,
                              description: String,
                              parameters: Map[String, String]) =
    ExperimentQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_test1"), VariableId("college_math")),
      Nil,
      "",
      List(Algorithm(algorithm, description, parameters)),
      List(Validation("kfold", "kfold", Map("k" -> "2")))
    )

  private def waitFor[T](future: Future[Any])(implicit timeout: Timeout): Try[T] = {
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
    override protected def printLeaf(j: JsValue, sb: java.lang.StringBuilder): Unit =
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
