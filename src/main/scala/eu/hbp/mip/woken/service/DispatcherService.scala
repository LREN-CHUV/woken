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

package eu.hbp.mip.woken.service

import java.time.OffsetDateTime

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl.Source
import org.slf4j.LoggerFactory
import eu.hbp.mip.woken.config.Dataset
import eu.hbp.mip.woken.fp.Traverse
import eu.hbp.mip.woken.messages.external.{ DatasetId, ExternalAPIProtocol, QueryResult }
import eu.hbp.mip.woken.backends.HttpClient
import eu.hbp.mip.woken.core.CoordinatorConfig
import eu.hbp.mip.woken.core.model.Shapes
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation

import scala.concurrent.{ ExecutionContext, Future }
import cats.data._
import cats.implicits._
import spray.json._

class DispatcherService(
    datasets: Map[DatasetId, Dataset],
    coordinatorConfig: CoordinatorConfig
)(implicit val system: ActorSystem, implicit val materializer: ActorMaterializer)
    extends DefaultJsonProtocol
    with SprayJsonSupport {

  def dispatchTo(dataset: DatasetId): Option[Uri] =
    if (datasets.isEmpty)
      None
    else
      datasets.get(dataset).flatMap(_.location).map { l =>
        Uri(l.url)
      }

  def dispatchTo(datasets: Set[DatasetId]): (Set[Uri], Boolean) = {
    val urls     = datasets.map(dispatchTo)
    val local    = urls.contains(None)
    val maybeSet = Traverse.sequence(urls.filter(_.nonEmpty))

    (maybeSet.getOrElse(Set.empty), local)
  }

  // TODO: keep outgoing connections flows
  // val connectionFlow: Flow[HttpRequest, HttpResponse, _] = Http().outgoingConnection("host.com")
  // def pathToRequest(path : String) = HttpRequest(uri=Uri.Empty.withPath(Path(path)))
  // val reqFlow = Flow[String] map pathToRequest
  // see https://stackoverflow.com/questions/37659421/what-is-the-best-way-to-combine-akka-http-flow-in-a-scala-stream-flow?rq=1
  def remoteDispatchFlow(datasets: Set[DatasetId]): Source[(Uri, QueryResult), NotUsed] = {
    import HttpClient.{ Get, sendReceive }
    import ExternalAPIProtocol._

    implicit val ec: ExecutionContext = system.dispatcher

    Source(dispatchTo(datasets)._1)
      .buffer(100, OverflowStrategy.backpressure)
      .map(url => sendReceive(Get(url)).map((url, _)))
      .mapAsync(4)(identity)
      .mapAsync(1) {
        case (url, response) if response.status.isSuccess() =>
          (url.pure[Future], Unmarshal(response).to[QueryResult]).mapN((_, _))
        case (url, response) =>
          (url,
           QueryResult("",
                       coordinatorConfig.jobsConf.node,
                       OffsetDateTime.now(),
                       Shapes.error.mime,
                       "dispatch",
                       None,
                       Some(response.entity.toString))).pure[Future]
      }
//      .fold[List[(Uri, QueryResult)]](List()) { (l, r) =>
//        l :+ r
//      }

  }

}

object DispatcherService {

  private val logger = LoggerFactory.getLogger("DispatcherService")

  private[service] def loadDatasets(
      datasets: Validation[Map[DatasetId, Dataset]]
  ): Map[DatasetId, Dataset] =
    datasets.getOrElse {
      logger.info("No datasets configured")
      Map.empty
    }

  def apply(
      datasets: Validation[Map[DatasetId, Dataset]],
      coordinatorConfig: CoordinatorConfig
  )(implicit system: ActorSystem, materializer: ActorMaterializer): DispatcherService =
    new DispatcherService(loadDatasets(datasets), coordinatorConfig)

}
