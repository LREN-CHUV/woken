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
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.dao._
import ch.chuv.lren.woken.messages.datasets.Dataset
import com.typesafe.scalalogging.Logger
import doobie.hikari.HikariTransactor
import org.slf4j.LoggerFactory
import sup.data.Tagged
import sup.{ HealthReporter, mods }

import scala.language.higherKinds

case class DatabaseServices[F[_]: ConcurrentEffect: ContextShift: Timer](
    config: WokenConfiguration,
    featuresService: FeaturesService[F],
    wokenRepository: WokenRepository[F],
    variablesMetaService: VariablesMetaRepository[F],
    queryToJobService: QueryToJobService[F],
    datasetService: DatasetService,
    algorithmLibraryService: AlgorithmLibraryService
) {

  import DatabaseServices.logger

  def jobResultService: JobResultRepository[F]       = wokenRepository.jobResults
  def resultsCacheService: ResultsCacheRepository[F] = wokenRepository.resultsCache
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def validate(): F[Unit] = {

    logger.info("Check configuration of datasets...")
    logger.info(datasetService.datasets().values.filter(_.location.isEmpty).toString())

    implicit val FPlus: Monoid[F[Unit]] = new Monoid[F[Unit]] {
      def empty: F[Unit]                           = Effect[F].pure(())
      def combine(x: F[Unit], y: F[Unit]): F[Unit] = x.flatMap(_ => y)
    }

    // Validate local datasets
    Monoid
      .combineAll(datasetService.datasets().values.filter(_.location.isEmpty).map { dataset =>
        Monoid
          .combineAll(dataset.tables.map { table =>
            {
              featuresService
                .featuresTable(table)
                .fold[F[Unit]](
                  { error: NonEmptyList[String] =>
                    val errMsg = error.mkString_(",")
                    logger.error(errMsg)
                    Effect[F].raiseError(new IllegalStateException(errMsg))
                  }, { table: FeaturesTableService[F] =>
                    validateTable(dataset, table)
                  }
                )
            }
          })
          .map(
            _ => {
              val tables = dataset.tables.map(_.toString).mkString(",")
              logger.info(
                s"Dataset ${dataset.id.code} (${dataset.label}) registered locally on tables $tables"
              )
            }
          )
      })
      .map(_ => logger.info("[OK] Datasets are valid"))

  }

  private def validateTable(dataset: Dataset, table: FeaturesTableService[F]): F[Unit] =
    for {
      _         <- tableShouldContainRowsForDataset(dataset, table)
      variables <- tableShouldHaveMetadataDefined(table)
      _         <- tableFieldsShouldMatchMetadata(table, variables)
    } yield ()

  private def tableShouldContainRowsForDataset(dataset: Dataset,
                                               table: FeaturesTableService[F]): F[Unit] =
    table
      .count(dataset.id)
      .flatMap[Unit] { count =>
        if (count == 0) {
          val error =
            s"Table ${table.table} contains no value for dataset ${dataset.id.code}"
          logger.error(error)
          Effect[F].raiseError(new IllegalStateException(error))
        } else ().pure[F]
      }

  private def tableShouldHaveMetadataDefined(table: FeaturesTableService[F]): F[VariablesMeta] = {
    val tableId = table.table.table
    variablesMetaService
      .get(tableId)
      .flatMap(
        metaO =>
          metaO.fold(
            Effect[F].raiseError[VariablesMeta](
              new IllegalStateException(
                s"Cannot find metadata for table ${tableId.toString}"
              )
            )
          )(_.pure[F])
      )
  }

  private def tableFieldsShouldMatchMetadata(table: FeaturesTableService[F],
                                             variables: VariablesMeta): F[Unit] =
    table
      .validateFields(variables.allVariables())
      .map { validation =>
        validation.fold(
          err => Effect[F].raiseError(new IllegalStateException(err.map(_._2.msg).mkString_(", "))),
          warnings => Effect[F].delay(warnings.foreach(w => logger.warn(w.msg)))
        )
      }

  def close(): F[Unit] = Effect[F].pure(())

  type TaggedS[H] = Tagged[String, H]
  lazy val healthChecks: HealthReporter[F, NonEmptyList, TaggedS] = {
    val featuresCheck =
      featuresService.healthCheck.through[F, TaggedS](mods.tagWith("Features database"))
    val jobsCheck =
      jobResultService.healthCheck.through[F, TaggedS](mods.tagWith("Woken jobs database"))
    val variablesCheck =
      variablesMetaService.healthCheck.through[F, TaggedS](mods.tagWith("Metadata database"))

    HealthReporter.fromChecks(featuresCheck, jobsCheck, variablesCheck)
  }

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

    logger.info("Connect to databases...")

    val transactors: Resource[F, Transactors[F]] = for {
      featuresTransactor <- DatabaseConfiguration.dbTransactor[F](config.featuresDb)
      resultsTransactor  <- DatabaseConfiguration.dbTransactor[F](config.resultsDb)
      metaTransactor     <- DatabaseConfiguration.dbTransactor[F](config.metaDb)
    } yield Transactors[F](featuresTransactor, resultsTransactor, metaTransactor)

    transactors.flatMap { t =>
      val wokenIO: F[WokenRepository[F]] = mkService(t.resultsTransactor, config.resultsDb) { xa =>
        Sync[F].delay(WokenRepositoryDAO(xa))
      }

      val fsIO: F[FeaturesService[F]] = wokenIO.flatMap { wokenRepository =>
        mkService(t.featuresTransactor, config.featuresDb) { xa =>
          FeaturesRepositoryDAO(xa, config.featuresDb, wokenRepository).map {
            _.map { FeaturesService.apply[F] }
          }
        }.map(_.valueOr(configurationFailed))
      }

      val vmsIO: F[VariablesMetaRepository[F]] = mkService(t.metaTransactor, config.metaDb) { xa =>
        Sync[F].delay(MetadataRepositoryDAO(xa).variablesMeta)
      }

      val datasetService          = ConfBasedDatasetService(config.config, config.jobs)
      val algorithmLibraryService = AlgorithmLibraryService()

      val servicesIO = for {
        featuresService      <- fsIO
        wokenService         <- wokenIO
        variablesMetaService <- vmsIO
        queryToJobService = QueryToJobService(featuresService,
                                              variablesMetaService,
                                              config.jobs,
                                              config.algorithmLookup)
      } yield
        DatabaseServices[F](config,
                            featuresService,
                            wokenService,
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
      _ <- Async[F].delay(
        logger.info(s"[OK] Connected to database ${dbConfig.database} on ${dbConfig.jdbcUrl}")
      )
    } yield {
      validatedDb
    }

}
