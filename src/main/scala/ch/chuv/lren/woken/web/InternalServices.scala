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
import ch.chuv.lren.woken.config.{ DatabaseConfiguration, WokenConfiguration, configurationFailed }
import ch.chuv.lren.woken.dao.{ FeaturesRepositoryDAO, WokenRepositoryDAO }
import ch.chuv.lren.woken.service.{ DatabaseServices, FeaturesService, JobResultService }
import doobie.hikari.HikariTransactor

/**
  * Provides a Resource containing the configured services.
  *
  */
object InternalServices {

  case class Transactors(featuresTransactor: HikariTransactor[IO],
                         resultsTransactor: HikariTransactor[IO])

  def provide(config: WokenConfiguration): Resource[IO, DatabaseServices] = {
    val transactors: Resource[IO, Transactors] = for {
      featuresTransactor <- DatabaseConfiguration.dbTransactor(config.featuresDb)
      resultsTransactor  <- DatabaseConfiguration.dbTransactor(config.resultsDb)
    } yield Transactors(featuresTransactor, resultsTransactor)

    transactors.flatMap { t =>
      val fsIO: IO[FeaturesService] = for {
        validatedXa <- DatabaseConfiguration
          .validate(t.featuresTransactor, config.featuresDb)
          .map(_.valueOr(configurationFailed))
        validatedDb <- FeaturesRepositoryDAO(validatedXa, config.featuresDb.tables)
          .map { daoV =>
            daoV.map { FeaturesService.apply }
          }
      } yield {
        validatedDb.valueOr(configurationFailed)
      }

      val jrsIO: IO[JobResultService] = for {
        validatedXa <- DatabaseConfiguration
          .validate(t.resultsTransactor, config.resultsDb)
          .map(_.valueOr(configurationFailed))
        validatedDb <- IO.pure(JobResultService(WokenRepositoryDAO(validatedXa).jobResults))
      } yield {
        validatedDb
      }

      val servicesIO = for {
        featuresService  <- fsIO
        jobResultService <- jrsIO
      } yield DatabaseServices(featuresService, jobResultService)

      Resource.liftF(servicesIO)

    }
  }

}
