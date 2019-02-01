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

package ch.chuv.lren.woken.service

import cats.data.NonEmptyList
import cats.effect._
import ch.chuv.lren.woken.backends.faas.AlgorithmExecutor
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.errors.ErrorReporter
import sup.HealthReporter
import sup.data.Tagged
import sup.data.Tagged._

import scala.language.higherKinds

case class BackendServices[F[_]: Effect](dispatcherService: DispatcherService,
                                         algorithmExecutor: AlgorithmExecutor[F],
                                         wokenWorker: WokenWorker[F],
                                         miningCacheService: MiningCacheService[F],
                                         errorReporter: ErrorReporter) {

  type TaggedS[H] = Tagged[String, H]
  lazy val healthChecks: HealthReporter[F, NonEmptyList, TaggedS] =
    HealthReporter.fromChecks(algorithmExecutor.healthCheck,
                              wokenWorker.scoringServiceHealthCheck,
                              wokenWorker.validationServiceHealthCheck)

}
