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

import akka.actor.{ ActorRef, ActorSystem }
import cats.effect.Effect
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }

import scala.language.higherKinds

class DispatchActors(
    val metadataQueriesWorker: ActorRef,
    val miningQueriesWorker: ActorRef,
    val experimentQueriesWorker: ActorRef
)

object DispatchActors {

  def apply[F[_]: Effect](
      system: ActorSystem,
      config: WokenConfiguration,
      backendServices: BackendServices[F],
      databaseServices: DatabaseServices[F]
  ): DispatchActors = {

    val metadataQueriesWorker: ActorRef =
      system.actorOf(
        MetadataQueriesActor.roundRobinPoolProps(
          config.config,
          backendServices.dispatcherService,
          databaseServices.datasetService,
          databaseServices.variablesMetaService
        ),
        name = "metadataQueries"
      )

    val miningQueriesWorker: ActorRef =
      system.actorOf(
        MiningQueriesActor.roundRobinPoolProps(
          config,
          databaseServices,
          backendServices
        ),
        name = "miningQueries"
      )

    val experimentQueriesWorker: ActorRef =
      system.actorOf(
        ExperimentQueriesActor.roundRobinPoolProps(
          config,
          databaseServices,
          backendServices
        ),
        name = "experimentQueries"
      )

    new DispatchActors(metadataQueriesWorker, miningQueriesWorker, experimentQueriesWorker)
  }
}
