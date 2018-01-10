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
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import eu.hbp.mip.woken.config.{DatasetLocation, DatasetsConfiguration}
import eu.hbp.mip.woken.fp.Traverse
import eu.hbp.mip.woken.messages.external.{DatasetId, QueryResult}
import eu.hbp.mip.woken.backends.HttpClient

import scala.concurrent.Future
import cats.implicits._
import eu.hbp.mip.woken.core.CoordinatorConfig
import eu.hbp.mip.woken.core.model.Shapes


class DispatcherService(config: Config, coordinatorConfig: CoordinatorConfig) {
  import DispatcherService._

  val datasets: Map[DatasetId, DatasetLocation] = loadDatasets(config)

  def dispatchTo(dataset: DatasetId): Option[Uri] =
    if (datasets.isEmpty)
      None
    else
      datasets.get(dataset).flatMap(_.location).map(Uri(_))

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
  def remoteDispatchFlow(datasets: Set[DatasetId]): Source[QueryResult] = {
    import HttpClient._

    Source(dispatchTo(datasets)._1).buffer(100, OverflowStrategy.backpressure)
      .map(url => sendReceive(Get(url)).map((url, _)))
      .mapAsync(4)(identity)
      .map {
        case (url, response) if response.status.isSuccess() =>
          (url, Unmarshal(response).to[QueryResult])
        case (url, response) =>
          (url, QueryResult("",
            coordinatorConfig.jobsConf.node,
            OffsetDateTime.now(),
            Shapes.error.mime,
            "dispatch",
            None,
            Some(response.entity.toString)))
      }
      .mapConcat(identity)

  }

  def chainRequests(reqOption: Option[HttpRequest]): Future[Option[(Option[HttpRequest], HttpResponse)]] =
    reqOption match {
      case Some(req) => Http().singleRequest(req).flatMap { response =>
        // handle the error case. Here we just return the errored response
        // with no next item.
        if (response.status.isFailure()) Future.successful(Some(None -> response))

        // Otherwise, convert the response to a strict response by
        // taking up the body and looking for a next request.
        else convertToStrict(response).map { strictResponse =>
          getNextRequest(strictResponse) match {
            // If we have no next request, return Some containing an
            // empty state, but the current value
            case None => Some(None -> strictResponse)

            // Otherwise, pass on the request...
            case next => Some(next -> strictResponse)
          }
        }
      }
      // Finally, there's no next request, end the stream by
      // returning none as the state.
      case None => Future.successful(None)
    }

  def convertToStrict(r: HttpResponse): Future[HttpResponse] =
    r.entity.toStrict(10.minutes).map(e => r.withEntity(e))

}

object DispatcherService {

  private val logger = LoggerFactory.getLogger("DispatcherService")

  private[service] def loadDatasets(config: Config): Map[DatasetId, DatasetLocation] =
    DatasetsConfiguration.datasets(config).getOrElse {
      logger.info("No datasets configured")
      Map.empty
    }

  def apply(config: Config): DispatcherService = new DispatcherService(config)

}
