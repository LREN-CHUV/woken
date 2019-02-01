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

import akka.pattern.ask
import akka.util.Timeout
import cats.effect.{ ConcurrentEffect, ContextShift, Timer }
import cats.implicits._
import ch.chuv.lren.woken.akka.AkkaServer
import ch.chuv.lren.woken.core.model.database.FeaturesTableDescription
import ch.chuv.lren.woken.core.fp.runNow
import ch.chuv.lren.woken.messages.query.{ AlgorithmSpec, MiningQuery, QueryResult, UserId }
import ch.chuv.lren.woken.messages.variables.VariableId
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.language.higherKinds

trait MiningCacheService[F[_]] {

  /**
    * Prefill the cache with histograms and summary statistics results for all variables and all registered tables
    */
  def prefill(): Unit

  /**
    * Run maintainance tasks, to be scheduled regularly as part of preventive maintenance
    */
  def maintainCache(): Unit

  /**
    * Force a full reset of the cache
    */
  def resetCache(): Unit
}

object MiningCacheService {
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer](
      akkaServer: AkkaServer[F]
  ): MiningCacheService[F] = new MiningCacheServiceImpl[F](akkaServer)
}

class MiningCacheServiceImpl[F[_]: ConcurrentEffect: ContextShift: Timer](akkaServer: AkkaServer[F])
    extends MiningCacheService[F]
    with LazyLogging {

  def prefill(): Unit = {
    implicit val timeout: Timeout = 10.minutes

    val tables               = akkaServer.databaseServices.config.featuresDb.tables
    val variablesMetaService = akkaServer.databaseServices.variablesMetaService

    tables.foreach { table =>
      // TODO: add support for table schema
      runNow(variablesMetaService.get(table.table.name).map { metaO =>
        metaO.foreach {
          variables =>
            variables.allVariables().foreach {
              variable =>
                val histogramAlgorithm         = AlgorithmSpec("histogram", Nil, None)
                val histogramQuery             = queryFor(histogramAlgorithm, table, variable.toId)
                val statisticsSummaryAlgorithm = AlgorithmSpec("statisticsSummary", Nil, None)
                val statisticsSummaryQuery =
                  queryFor(statisticsSummaryAlgorithm, table, variable.toId)

                waitFor((akkaServer.mainRouter ? histogramQuery).mapTo[QueryResult])
                waitFor((akkaServer.mainRouter ? statisticsSummaryQuery).mapTo[QueryResult])

            }
        }
      })
      logger.info(
        s"Prefilled cache of histograms and summary statistics for table ${table.table.name}"
      )
    }
  }

  /**
    * Run maintainance tasks, to be scheduled regularly as part of preventive maintenance
    */
  override def maintainCache(): Unit =
    runNow(akkaServer.databaseServices.resultsCacheService.clean())

  /**
    * Force a full reset of the cache
    */
  override def resetCache(): Unit =
    runNow(akkaServer.databaseServices.resultsCacheService.reset())

  private def waitFor(f: Future[QueryResult]): Unit = {
    val result = Await.result(f, 10.minutes)
    result.error.foreach(err => logger.warn(err))
  }

  private val systemUser = UserId("woken")

  private def queryFor(algorithm: AlgorithmSpec,
                       table: FeaturesTableDescription,
                       variable: VariableId) =
    MiningQuery(systemUser,
                List(variable),
                Nil,
                covariablesMustExist = false,
                Nil,
                None,
                Some(table.table.name),
                Set(),
                algorithm,
                None)
}
