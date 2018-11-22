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

import cats.effect._
import cats.implicits._

import ch.chuv.lren.woken.config.{ DatabaseConfiguration, WokenConfiguration, configurationFailed }
import ch.chuv.lren.woken.dao.{ FeaturesRepositoryDAO, MetadataRepositoryDAO, WokenRepositoryDAO }
import doobie.hikari.HikariTransactor
import scala.language.higherKinds

case class DatabaseServices[F[_]](featuresService: FeaturesService[F],
                                  jobResultService: JobResultService[F],
                                  variablesMetaService: VariablesMetaService[F])

/**
  * Provides a Resource containing the configured services.
  *
  */
object DatabaseServices {

  case class Transactors[F[_]](featuresTransactor: HikariTransactor[F],
                               resultsTransactor: HikariTransactor[F],
                               metaTransactor: HikariTransactor[F])
  def resource[F[_]: ConcurrentEffect: ContextShift: Timer](
      config: WokenConfiguration
  )(implicit cs: ContextShift[IO]): Resource[F, DatabaseServices[F]] = {

    val transactors: Resource[F, Transactors[F]] = for {
      featuresTransactor <- DatabaseConfiguration.dbTransactor[F](config.featuresDb)
      resultsTransactor  <- DatabaseConfiguration.dbTransactor[F](config.resultsDb)
      metaTransactor     <- DatabaseConfiguration.dbTransactor[F](config.metaDb)
    } yield Transactors[F](featuresTransactor, resultsTransactor, metaTransactor)

    transactors.flatMap { t =>
      val fsIO: F[FeaturesService[F]] = mkService(t.featuresTransactor, config.featuresDb) { xa =>
        FeaturesRepositoryDAO(xa, config.featuresDb.tables).map {
          _.map { FeaturesService.apply[F] }
        }
      }.map(_.valueOr(configurationFailed))

      val jrsIO: F[JobResultService[F]] = mkService(t.resultsTransactor, config.resultsDb) { xa =>
        Sync[F].delay(JobResultService(WokenRepositoryDAO(xa).jobResults))
      }

      val vmsIO: F[VariablesMetaService[F]] = mkService(t.metaTransactor, config.metaDb) { xa =>
        Sync[F].delay(VariablesMetaService(MetadataRepositoryDAO(xa).variablesMeta))
      }

      val servicesIO = for {
        featuresService      <- fsIO
        jobResultService     <- jrsIO
        variablesMetaService <- vmsIO
      } yield DatabaseServices[F](featuresService, jobResultService, variablesMetaService)

      Resource.liftF(servicesIO)
    }
  }

  private[this] def mkService[F[_]: ConcurrentEffect: ContextShift, M](
      transactor: HikariTransactor[F],
      dbConfig: DatabaseConfiguration
  )(
      serviceGen: HikariTransactor[F] => F[M]
  )(implicit cs: ContextShift[IO]): F[M] =
    for {
      validatedXa <- DatabaseConfiguration
        .validate(transactor, dbConfig)
        .map(_.valueOr(configurationFailed))
      validatedDb <- serviceGen(validatedXa)
    } yield {
      validatedDb
    }

}
