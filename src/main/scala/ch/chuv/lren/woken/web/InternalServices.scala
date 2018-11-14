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

package ch.chuv.lren.woken.web

import cats.effect.{ IO, Resource }
import cats.implicits._
import ch.chuv.lren.woken.config.{ DatabaseConfiguration, WokenConfiguration, configurationFailed }
import ch.chuv.lren.woken.dao.{ FeaturesRepositoryDAO, MetadataRepositoryDAO, WokenRepositoryDAO }
import ch.chuv.lren.woken.service.{
  DatabaseServices,
  FeaturesService,
  JobResultService,
  VariablesMetaService
}
import doobie.hikari.HikariTransactor

/**
  * Provides a Resource containing the configured services.
  *
  */
object InternalServices {

  case class Transactors(featuresTransactor: HikariTransactor[IO],
                         resultsTransactor: HikariTransactor[IO],
                         metaTransactor: HikariTransactor[IO])

  def provide(config: WokenConfiguration): Resource[IO, DatabaseServices] = {

    val transactors: Resource[IO, Transactors] = for {
      featuresTransactor <- DatabaseConfiguration.dbTransactor(config.featuresDb)
      resultsTransactor  <- DatabaseConfiguration.dbTransactor(config.resultsDb)
      metaTransactor     <- DatabaseConfiguration.dbTransactor(config.metaDb)
    } yield Transactors(featuresTransactor, resultsTransactor, metaTransactor)

    transactors.flatMap { t =>
      val fsIO: IO[FeaturesService] = mkService(t.featuresTransactor, config.featuresDb) { xa =>
        FeaturesRepositoryDAO(xa, config.featuresDb.tables).map { _.map { FeaturesService.apply } }
      }.map(_.valueOr(configurationFailed))

      val jrsIO: IO[JobResultService] = mkService(t.resultsTransactor, config.resultsDb) { xa =>
        IO.pure(JobResultService(WokenRepositoryDAO(xa).jobResults))
      }

      val vmsIO: IO[VariablesMetaService] = mkService(t.metaTransactor, config.metaDb) { xa =>
        IO.pure(VariablesMetaService(MetadataRepositoryDAO(xa).variablesMeta))
      }

      val servicesIO = for {
        featuresService      <- fsIO
        jobResultService     <- jrsIO
        variablesMetaService <- vmsIO
      } yield DatabaseServices(featuresService, jobResultService, variablesMetaService)

      Resource.liftF(servicesIO)
    }
  }

  private[this] def mkService[M](transactor: HikariTransactor[IO], dbConfig: DatabaseConfiguration)(
      serviceGen: HikariTransactor[IO] => IO[M]
  ): IO[M] =
    for {
      validatedXa <- DatabaseConfiguration
        .validate(transactor, dbConfig)
        .map(_.valueOr(configurationFailed))
      validatedDb <- serviceGen(validatedXa)
    } yield {
      validatedDb
    }

}
