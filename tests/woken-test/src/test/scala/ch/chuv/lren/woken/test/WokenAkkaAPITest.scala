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

import java.util.concurrent.{Semaphore, TimeUnit}

import akka.actor.{ActorRef, ActorSystem}
import akka.cluster.Cluster
import akka.cluster.pubsub.{DistributedPubSub, DistributedPubSubMediator}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import ch.chuv.lren.woken.messages.datasets._
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableId
import com.typesafe.scalalogging.LazyLogging
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import org.scalatest.TryValues._
import org.scalatest.tagobjects.Slow
import spray.json._
import queryProtocol._

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

class WokenAkkaAPITest
    extends WordSpec
    with Matchers
    with Queries
    with BeforeAndAfterAll
    with LazyLogging {

  implicit val timeout: Timeout = Timeout(200 seconds)
  val config: Config =
    ConfigFactory
      .parseString("""
          |akka {
          |  actor.provider = cluster
          |  extensions += "akka.cluster.pubsub.DistributedPubSub"
          |}
        """.stripMargin)
      .withFallback(ConfigFactory.load())
      .resolve()

  implicit val system: ActorSystem = ActorSystem("woken", config)
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher

  val cluster = Cluster(system)
  val mediator: ActorRef = DistributedPubSub(system).mediator

  val entryPoint = "/user/entrypoint"

  override def beforeAll: Unit = {
    val waitClusterUp = new Semaphore(1)

    cluster.registerOnMemberUp(waitClusterUp.release())

    waitClusterUp.acquire()

    // TODO: Woken should response to a Ping message
    Thread.sleep(5000)

  }

  override def afterAll: Unit = {
    cluster.leave(cluster.selfAddress)
    system.terminate().onComplete { result =>
      logger.debug(s"Actor system shutdown: $result")
    }
  }

  "Woken" should {

    "respond to a query for the list of methods" in {
      val response: MethodsResponse =
        timedQuery(MethodsQuery, "list of methods")
      val expected = loadJson("/list_algorithms.json")

      response.methods shouldBe expected
    }

    "respond to a query for the list of available datasets" in {
      val response: DatasetsResponse =
        timedQuery(DatasetsQuery(Some("cde_features_a")), "list of datasets")

      response.datasets should have size 1

      val expected = Set(
        Dataset(DatasetId("desd-synthdata"),
                "DESD",
                "Demo dataset DESD",
                List("cde_features_a", "cde_features_mixed"),
                AnonymisationLevel.Anonymised,
                None))

      response.datasets shouldBe expected
    }

    "respond to a data mining query," which {

      "uses a k-NN algorithm               [PFA]" in {
        val query = MiningQuery(
          user = UserId("test1"),
          variables = List(VariableId("cognitive_task2")),
          covariables = List(VariableId("score_math_course1")),
          grouping = Nil,
          filters = None,
          targetTable = Some("sample_data"),
          algorithm = AlgorithmSpec("knn", List(CodeValue("k", "5"))),
          datasets = Set(),
          executionPlan = None
        )
        val response: QueryResult =
          timedQuery(query, "mine data using k-NN algorithm")

        response.data should not be empty

        val json = response.toJson
        val expected = loadJson("/knn_data_mining.json")

        assertResult(approximate(expected))(approximate(json))
      }

      "uses a histogram                    [visualisation, highcharts]" in {
        val query = MiningQuery(
          user = UserId("test1"),
          variables = List(VariableId("cognitive_task2")),
          covariables =
            List("score_math_course1", "score_math_course2").map(VariableId),
          grouping = Nil,
          filters = None,
          targetTable = Some("sample_data"),
          algorithm = AlgorithmSpec("histograms", Nil),
          datasets = Set(),
          executionPlan = None
        )

        val response: QueryResult =
          timedQuery(query, "mine data using a histogram")

        response.data should not be empty

        val json = response.toJson
        val expected = loadJson("/histograms.json")

        assertResult(approximate(expected))(approximate(json))
      }

      "uses a summary statistics algorithm [visualisation, tabular results]" in {
        val query = MiningQuery(
          user = UserId("test1"),
          variables = List(VariableId("cognitive_task2")),
          covariables = List(),
          grouping = Nil,
          filters = None,
          targetTable = Some("sample_data"),
          algorithm = AlgorithmSpec("statisticsSummary", Nil),
          datasets = Set(),
          executionPlan = None
        )

        val response: QueryResult =
          timedQuery(query, "mine data using summary statistics algorithm")

        response.data should not be empty

        val json = response.toJson
        val expected = loadJson("/summary_statistics.json")

        assertResult(approximate(expected))(approximate(json))
      }

      "uses t-SNE                          [visualisation, highcharts]" in {
        val query = MiningQuery(
          user = UserId("test1"),
          variables = List(VariableId("cognitive_task2")),
          covariables =
            List("score_math_course1", "score_math_course2").map(VariableId),
          grouping = Nil,
          filters = None,
          targetTable = Some("sample_data"),
          algorithm = AlgorithmSpec("tSNE", Nil),
          datasets = Set(),
          executionPlan = None
        )

        val response: QueryResult = timedQuery(query, "mine data using t-SNE")

        response.data should not be empty

        val json = response.toJson

        // t-SNE is not deterministic, cannot check exactly its results
        val skippedTags = List("series")
        val expected = loadJson("/tsne_data_mining.json")

        assertResult(approximate(expected, skippedTags))(
          approximate(json, skippedTags))
      }

      "uses correlation heatmap            [visualisation, plotly.js]" in {
        val query = MiningQuery(
          user = UserId("test1"),
          variables = List(VariableId("cognitive_task2")),
          covariables =
            List("score_math_course1", "score_math_course2").map(VariableId),
          grouping = Nil,
          filters = None,
          targetTable = Some("sample_data"),
          algorithm = AlgorithmSpec("correlationHeatmap", Nil),
          datasets = Set(),
          executionPlan = None
        )

        val response: QueryResult = timedQuery(query, "mine data using correlation heatmap")

        response.data should not be empty

        val json = response.toJson
        val expected = loadJson("/correlation_heatmap_data_mining.json")

        assertResult(approximate(expected))(approximate(json))
      }

    }

    "respond to an experiment query," which {

      // Test experiment query
      "executes a k-NN algorithm" in {

        val query =
          experimentQuery("knn", parameters = List(CodeValue("k", "5")))
        val response: QueryResult =
          timedQuery(query, "an experiment with k-NN algorithm")

        response.data should not be empty

        val json = response.toJson
        val expected = loadJson("/knn_experiment.json")

        assertResult(approximate(expected))(approximate(json))
      }

      "executes Linear regression and Anova algorithms" in {

        val query = multipleExperimentQuery(
          List(AlgorithmSpec("linearRegression", List()),
               AlgorithmSpec("anova", List())))
        val response: QueryResult =
          timedQuery(query, "an experiment with Linear regression algorithm")

        response.data should not be empty

        val json = response.toJson
        val expected = loadJson("/lr_and_anova_experiment.json")

        assertResult(approximate(expected))(approximate(json))
      }

      "executes a Naive Bayes algorithm" in {

        val query = experimentQuery(
          "naiveBayes",
          parameters = List(),
          variables = List(VariableId("alzheimerbroadcategory")),
          covariables = List(VariableId("lefthippocampus")),
          targetTable = Some("cde_features_mixed")
        )

        val response: QueryResult =
          timedQuery(query, "an experiment with Naive Bayes algorithm")

        response.data should not be empty

        val json = response.toJson
        val expected = loadJson("/naive_bayes_experiment.json")

        assertResult(approximate(expected))(approximate(json))
      }

      // sgdLinearModel
      // sgdNeuralNetwork
      // gradientBoosting
      // ggparci
      // hinmine
      // hedwig
    }

    // Test resiliency
    "recover from multiple failed experiments" taggedAs Slow in {

      // TODO: add never_end
      val failures = List("training_fails",
                          "invalid_json",
                          "invalid_pfa_syntax",
                          "invalid_pfa_semantics",
                          "no_results")

      val queries = failures.map(failure =>
        experimentQuery("chaos", List(CodeValue("failure", failure))))

      val futures = queries.map(
        query =>
          mediator ? DistributedPubSubMediator
            .Send(entryPoint, query, localAffinity = true))

      futures.foreach { f =>
        logger.info("Waiting for result from chaos algorithm...")
        val result = waitFor[QueryResult](f)
        if (result.isFailure) {
          logger.info(s"Chaos algorithm failed with ${result.failed.get}")
        } else {
          logger.info(s"Chaos algorithm returned ${result.success.value}")
        }
      }

      val knnQuery = experimentQuery("knn", List(CodeValue("k", "5")))
      val response: QueryResult =
        timedQuery(knnQuery, "an experiment with k-NN algorithm")

      response.data should not be empty

      val json = response.toJson
      val expected = loadJson("/knn_experiment.json")

      assertResult(approximate(expected))(approximate(json))

    }
  }

  private def waitFor[T](future: Future[Any])(
      implicit timeout: Timeout): Try[T] = {
    Try {
      Await.result(future, timeout.duration).asInstanceOf[T]
    }
  }

  private def timedQuery[R](query: Any, description: String): R = {
    val start = System.currentTimeMillis()
    val future = mediator ? DistributedPubSubMediator.Send(entryPoint,
                                                           query,
                                                           localAffinity = true)
    val result = waitFor[R](future)
    val end = System.currentTimeMillis()

    logger.info(
      s"Query for $description complete in " + Duration(end - start,
                                                        TimeUnit.MILLISECONDS))

    if (!result.isSuccess) {
      logger.error(result.toString)
    }
    assert(result.isSuccess, "Query returned a failure")

    result.success.value
  }
}
