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

import cats.Monoid
import cats.data.NonEmptyList
import cats.effect._
import cats.implicits._
import ch.chuv.lren.woken.config.{ DatabaseConfiguration, WokenConfiguration, configurationFailed }
import ch.chuv.lren.woken.dao.{ FeaturesRepositoryDAO, MetadataRepositoryDAO, WokenRepositoryDAO }
import com.typesafe.scalalogging.Logger
import doobie.hikari.HikariTransactor
import org.slf4j.LoggerFactory

import scala.language.higherKinds

case class DatabaseServices[F[_]: ConcurrentEffect: ContextShift: Timer](
    featuresService: FeaturesService[F],
    jobResultService: JobResultService[F],
    variablesMetaService: VariablesMetaService[F],
    queryToJobService: QueryToJobService[F],
    datasetService: DatasetService,
    algorithmLibraryService: AlgorithmLibraryService
) {

  import DatabaseServices.logger

  def validate(): F[Unit] = {

    logger.info("Check configuration of datasets...")

    implicit val FPlus: Monoid[F[Unit]] = new Monoid[F[Unit]] {
      def empty: F[Unit]                           = Effect[F].pure(())
      def combine(x: F[Unit], y: F[Unit]): F[Unit] = x.handleErrorWith(_ => y)
    }

    Monoid.combineAll(datasetService.datasets().filter(_.location.isEmpty).map { dataset =>
      Monoid.combineAll(dataset.tables.map {
        qualifiedTableName =>
          {
            val dbSchema =
              if (qualifiedTableName.contains(".")) Some(qualifiedTableName.split(".")(0)) else None
            val tableName =
              if (qualifiedTableName.contains(".")) qualifiedTableName.split(".")(1)
              else qualifiedTableName
            featuresService
              .featuresTable(dbSchema, tableName)
              .fold[F[Unit]](
                { error: NonEmptyList[String] =>
                  val errMsg = error.mkString_("", ",", "")
                  logger.error(errMsg)
                  Effect[F].raiseError(new IllegalStateException(errMsg))
                }, { table: FeaturesTableService[F] =>
                  table.count(dataset.dataset).map { count =>
                    if (count == 0) {
                      val error =
                        s"Table $tableName contains no value for dataset ${dataset.dataset.code}"
                      logger.error(error)
                      throw new IllegalStateException(error)
                    }
                  }
                }
              )
          }
      })
    })
  }

  def close(): F[Unit] = Effect[F].pure(())

}

/**
  * Provides a Resource containing the configured services.
  *
  */
object DatabaseServices {

  private val logger: Logger = Logger(LoggerFactory.getLogger("woken.DatabaseServices"))

  case class Transactors[F[_]](featuresTransactor: HikariTransactor[F],
                               resultsTransactor: HikariTransactor[F],
                               metaTransactor: HikariTransactor[F])

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
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
        FeaturesRepositoryDAO(xa, config.featuresDb.database, config.featuresDb.tables).map {
          _.map { FeaturesService.apply[F] }
        }
      }.map(_.valueOr(configurationFailed))

      val jrsIO: F[JobResultService[F]] = mkService(t.resultsTransactor, config.resultsDb) { xa =>
        Sync[F].delay(JobResultService(WokenRepositoryDAO(xa).jobResults))
      }

      val vmsIO: F[VariablesMetaService[F]] = mkService(t.metaTransactor, config.metaDb) { xa =>
        Sync[F].delay(VariablesMetaService(MetadataRepositoryDAO(xa).variablesMeta))
      }

      val datasetService          = ConfBasedDatasetService(config.config)
      val algorithmLibraryService = AlgorithmLibraryService()

      val servicesIO = for {
        featuresService      <- fsIO
        jobResultService     <- jrsIO
        variablesMetaService <- vmsIO
        queryToJobService = QueryToJobService(featuresService,
                                              variablesMetaService,
                                              config.jobs,
                                              config.algorithmLookup)
      } yield
        DatabaseServices[F](featuresService,
                            jobResultService,
                            variablesMetaService,
                            queryToJobService,
                            datasetService,
                            algorithmLibraryService)

      Resource.make(servicesIO.flatMap(service => service.validate().map(_ => service)))(_.close())
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
      _           <- Async[F].delay(logger.info(s"Connected to database ${dbConfig.database}"))
    } yield {
      validatedDb
    }

}
