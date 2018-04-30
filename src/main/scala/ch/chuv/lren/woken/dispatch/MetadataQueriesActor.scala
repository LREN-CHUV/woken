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

package ch.chuv.lren.woken.dispatch

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ Actor, ActorRef, OneForOneStrategy, Props }
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import ch.chuv.lren.woken.messages.variables.{
  VariableMetaData,
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}
import ch.chuv.lren.woken.service.{ DatasetService, DispatcherService, VariablesMetaService }
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

object MetadataQueriesActor extends LazyLogging {

  case class VariablesForDatasets(query: VariablesForDatasetsQuery, replyTo: ActorRef)

  def props(dispatcherService: DispatcherService,
            datasetService: DatasetService,
            variablesMetaService: VariablesMetaService): Props =
    Props(
      new MetadataQueriesActor(dispatcherService, datasetService, variablesMetaService)
    )

  def roundRobinPoolProps(config: Config,
                          dispatcherService: DispatcherService,
                          datasetService: DatasetService,
                          variablesMetaService: VariablesMetaService): Props = {

    val resizer = OptimalSizeExploringResizer(
      config
        .getConfig("poolResizer.metadataQueries")
        .withFallback(
          config.getConfig("akka.actor.deployment.default.optimal-size-exploring-resizer")
        )
    )
    val experimentSupervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case e: Exception =>
          logger.error("Error detected in Metadata queries actor, restarting", e)
          Restart
      }

    RoundRobinPool(
      1,
      resizer = Some(resizer),
      supervisorStrategy = experimentSupervisorStrategy
    ).props(
      MetadataQueriesActor.props(dispatcherService, datasetService, variablesMetaService)
    )
  }

}

class MetadataQueriesActor(dispatcherService: DispatcherService,
                           datasetService: DatasetService,
                           variablesMetaService: VariablesMetaService)
    extends Actor
    with LazyLogging {

  import MetadataQueriesActor.VariablesForDatasets

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  override def receive: Receive = {

    case varsForDataset: VariablesForDatasets =>
      val initiator = if (varsForDataset.replyTo == Actor.noSender) sender() else varsForDataset.replyTo
      val query     = varsForDataset.query

      logger.info(s"Received query $query")
      Source
        .single(
          query.copy(
            datasets = datasetService
              .datasets()
              .map(_.dataset)
              .filter(query.datasets.isEmpty || query.datasets.contains(_))
          )
        )
        .via(dispatcherService.dispatchVariablesQueryFlow(datasetService, variablesMetaService))
        .fold(VariablesForDatasetsResponse(Set())) {
          case (_, n) if n.error.isDefined => n
          case (p, _) if p.error.isDefined => p
          case (p, n)  => p.copy(variables = merge(p.variables, n.variables))
        }
        .map[VariablesForDatasetsResponse] { response =>
          logger.debug(s"Response $response")
          initiator ! response
          response
        }
        .runWith(Sink.last)
        .failed
        .foreach { e =>
          logger.error(s"Cannot complete variable query $query", e)
          initiator ! VariablesForDatasetsResponse(Set(), Some(e.getMessage))
        }

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def merge(variables: Set[VariableMetaData], otherVars: Set[VariableMetaData]): Set[VariableMetaData] = {

    variables.map { v =>
      otherVars
        .map(ov => v.merge(ov))
        .foldLeft(v) {
          case (_, Some(m)) => m
          case (s,_) => s
        }
    } ++ otherVars.filterNot { v =>
      variables.exists(_.isMergeable(v))
    }

  }

}
