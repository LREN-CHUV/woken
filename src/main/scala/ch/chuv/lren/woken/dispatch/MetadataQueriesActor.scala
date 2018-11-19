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
import cats.effect.Effect
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.messages.variables.{
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

  def props[F[_]: Effect](dispatcherService: DispatcherService,
                          datasetService: DatasetService,
                          variablesMetaService: VariablesMetaService[F]): Props =
    Props(
      new MetadataQueriesActor(dispatcherService, datasetService, variablesMetaService)
    )

  def roundRobinPoolProps[F[_]: Effect](config: Config,
                                        dispatcherService: DispatcherService,
                                        datasetService: DatasetService,
                                        variablesMetaService: VariablesMetaService[F]): Props = {

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

class MetadataQueriesActor[F[_]: Effect](dispatcherService: DispatcherService,
                                         datasetService: DatasetService,
                                         variablesMetaService: VariablesMetaService[F])
    extends Actor
    with LazyLogging {

  import MetadataQueriesActor.VariablesForDatasets

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  type VariablesForDatasetsQR = (VariablesForDatasetsQuery, VariablesForDatasetsResponse)

  override def receive: Receive = {

    case varsForDataset: VariablesForDatasets =>
      val initiator =
        if (varsForDataset.replyTo == Actor.noSender) sender() else varsForDataset.replyTo
      val query = varsForDataset.query.copy(
        datasets = datasetService
          .datasets()
          .map(_.dataset)
          .filter(
            varsForDataset.query.datasets.isEmpty || varsForDataset.query.datasets.contains(_)
          )
      )
      val zero = (query, VariablesForDatasetsResponse(Set()))

      logger.info(s"Received query $query")
      Source
        .single(query)
        .via(dispatcherService.dispatchVariablesQueryFlow(datasetService, variablesMetaService))
        .fold[VariablesForDatasetsQR](zero) {
          case (_, (q, n)) if n.error.isDefined => (q, n)
          case ((q, p), _) if p.error.isDefined => (q, p)
          case ((q1, p), (q2, n)) if q1 == q2 =>
            (q1, p.copy(variables = VariablesMeta.merge(p.variables, n.variables, q1.exhaustive)))
          case ((q1, p), (q2, n)) if q1 != q2 =>
            throw new IllegalStateException(
              s"Expected matching queries, got query $q1 and response $p with query $q2 and response $n"
            )
        }
        .map[VariablesForDatasetsResponse] { qr =>
          val response = qr._2
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

}
