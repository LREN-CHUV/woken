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

import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import eu.hbp.mip.woken.messages.external._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

class AkkaAPITest extends FlatSpec with Matchers {

  implicit val timeout: Timeout = Timeout(60 seconds)
  val system = ActorSystem("woken-test")

  // Test methods query
  "Woken" should "respond to a query for the list of methods" in {
    val ref =
      system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")

    val future = ref ? MethodsQuery()

    val result =
      try {
        Await.result(future, timeout.duration)
      } catch {
        case _: TimeoutException => this.fail("Timeout!")
        case _: Exception        => this.fail()
      }

    result should matchPattern { case m: Methods => }

    println(s"Methods: ${result.asInstanceOf[Methods].methods}")
  }

  // Test mining query
  "Woken" should "respond to a data mining query" in {
    val ref =
      system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")

    val future = ref ? MiningQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_math_course1")),
      Seq.empty[VariableId],
      "",
      Algorithm("knn",
                "K-nearest neighbors with k=5",
                Map[String, String]("k" -> "5"))
    )

    val result =
      try {
        Await.result(future, timeout.duration)
      } catch {
        case _: TimeoutException => this.fail("Timeout!")
        case _: Exception        => this.fail()
      }

    result should matchPattern { case r: QueryResult if r.data.isDefined => }

    println(s"Data mining returned ${result.asInstanceOf[QueryResult].data}")
  }

  // Test experiment query
  "Woken" should "respond to an experiment query" in {
    val ref =
      system.actorSelection("akka.tcp://woken@woken:8088/user/entrypoint")

    val future = ref ? ExperimentQuery(
      List(VariableId("cognitive_task2")),
      List(VariableId("score_test1"), VariableId("college_math")),
      Seq.empty[VariableId],
      "",
      List(
        Algorithm("knn",
                  "K-nearest neighbors with k=5",
                  Map[String, String]("k" -> "5"))),
      List(Validation("kfold", "kfold", Map("k" -> "2")))
    )

    val result =
      try {
        Await.result(future, timeout.duration)
      } catch {
        case _: java.util.concurrent.TimeoutException => this.fail("Timeout!")
        case _: Exception                             => this.fail()
      }

    result should matchPattern { case r: QueryResult if r.data.isDefined => }

    println(s"Experiment returned ${result.asInstanceOf[QueryResult].data}")
  }
}
