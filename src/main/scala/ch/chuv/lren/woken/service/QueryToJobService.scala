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

import java.util.UUID

import cats.data._
import cats.Monad
import cats.implicits._
import ch.chuv.lren.woken.config.JobsConfiguration
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.core.model.jobs.{ ExperimentJob, _ }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.dao.VariablesMetaRepository
import ch.chuv.lren.woken.messages.datasets.TableId
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.{ FeatureIdentifier, VariableId, VariableMetaData }
import com.typesafe.scalalogging.LazyLogging
import shapeless.{ ::, HNil }

import scala.language.higherKinds

private[service] case class JobStarting[Q <: Query, J <: Job[Q], F[_]](
    job: J,
    tableService: FeaturesTableService[F],
    feedback: UserFeedbacks
)

/**
  * Transform incoming mining and experiment queries into jobs
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
trait QueryToJobService[F[_]] {

  /**
    * Generate a job defining the execution of a mining query
    *
    * @param query the data mining query
    * @return a validated job, with local data provenance
    */
  def miningQuery2Job(query: MiningQuery): F[Validation[MiningJobInProgress]]

  /**
    * Generate a job defining the execution of a query for an experiment
    *
    * @param query the query for an experiment
    * @return a validated job, with local data provenance
    */
  def experimentQuery2Job(
      query: ExperimentQuery
  ): F[Validation[ExperimentJobInProgress]]

}

object QueryToJobService extends LazyLogging {

  // LATER Scala 3 - use opaque types
  type JobId         = String
  type FeaturesTable = TableId
  type PreparedQuery[Q <: Query] =
    JobId :: FeaturesTable :: List[VariableMetaData] :: Q :: UserFeedbacks :: HNil

  def apply[F[_]: Monad](
      featuresService: FeaturesService[F],
      variablesMetaService: VariablesMetaRepository[F],
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  ): QueryToJobService[F] =
    new QueryToJobServiceImpl[F](
      featuresService: FeaturesService[F],
      variablesMetaService: VariablesMetaRepository[F],
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
    )
}

class QueryToJobServiceImpl[F[_]: Monad](
    featuresService: FeaturesService[F],
    variablesMetaService: VariablesMetaRepository[F],
    jobsConfiguration: JobsConfiguration,
    algorithmLookup: String => Validation[AlgorithmDefinition]
) extends QueryToJobService[F]
    with LazyLogging {

  import QueryToJobService._

  override def miningQuery2Job(
      query: MiningQuery
  ): F[Validation[MiningJobInProgress]] = {
    logger.debug("Mining query to job")
    for {
      preparedQuery <- prepareQuery(variablesMetaService, jobsConfiguration, query)
      validatedQuery <- preparedQuery.fold(
        toInvalidF[PreparedQuery[MiningQuery]],
        pq => validateQuery(pq, featuresService)
      )
      annotatedJob <- validatedQuery
        .andThen(
          q =>
            createValidationOrMiningJob(q, algorithmLookup)
              .andThen(addLocalDataProvenance)
        )
        .fold(
          err => err.invalid[MiningJobInProgress].pure[F],
          fa => fa.map(_.validNel[String])
        )
    } yield annotatedJob
  }

  private[this] def createValidationOrMiningJob(
      preparedQuery: PreparedQuery[MiningQuery],
      algorithmLookup: String => Validation[AlgorithmDefinition]
  ): Validation[JobStarting[MiningQuery, Job[MiningQuery], F]] = {
    logger.debug("Create validation or mining job")

    val jobId :: featuresTable :: metadata :: query :: feedback :: HNil =
      preparedQuery

    def createMiningJob(mt: List[VariableMetaData],
                        q: MiningQuery,
                        ad: AlgorithmDefinition): MiningJob = {
      val featuresQuery = q
        .filterNulls(ad.variablesCanBeNull, ad.covariablesCanBeNull)
        .features(featuresTable, None)

      val dockerJob = DockerJob(jobId, featuresQuery, q.algorithm, ad, metadata = mt)
      MiningJob(q, dockerJob)
    }

    featuresService.featuresTable(featuresTable).andThen { fts =>
      logger.debug("Create job")

      val featuresTableDescription = fts.table

      val job: Validation[Job[MiningQuery]] = query.algorithm.code match {
        case ValidationJob.algorithmCode =>
          ValidationJob[F](jobId = jobId,
                           featuresTableService = fts,
                           query = query,
                           metadata = metadata)
            .validNel[String]

        case code =>
          val queryForDatasets = query.filterDatasets(featuresTableDescription.datasetColumn)
          algorithmLookup(code)
            .map(algorithm => createMiningJob(metadata, queryForDatasets, algorithm))
      }

      job.map(j => JobStarting[MiningQuery, Job[MiningQuery], F](j, fts, feedback))
    }

  }

  override def experimentQuery2Job(
      query: ExperimentQuery
  ): F[Validation[ExperimentJobInProgress]] =
    for {
      preparedQuery <- prepareQuery(variablesMetaService, jobsConfiguration, query)
      validatedQuery <- preparedQuery.fold(
        toInvalidF[PreparedQuery[ExperimentQuery]],
        pq => validateQuery(pq, featuresService)
      )
      annotatedJob <- validatedQuery
        .andThen(
          q =>
            createExperimentJob(q, algorithmLookup)
              .andThen(addLocalDataProvenance)
        )
        .fold(
          err => err.invalid[ExperimentJobInProgress].pure[F],
          fa => fa.map(_.validNel[String])
        )
    } yield annotatedJob

  private[this] def addLocalDataProvenance[Q <: Query, J <: Job[Q]](
      r: JobStarting[Q, J, F]
  ): Validation[F[JobInProgress[Q, J]]] =
    r.tableService
      .datasets(r.job.filters)
      .map(ds => JobInProgress[Q, J](r.job, ds, r.feedback))
      .validNel[String]

  private[this] def createExperimentJob(
      preparedQuery: PreparedQuery[ExperimentQuery],
      algorithmLookup: String => Validation[AlgorithmDefinition]
  ): Validation[JobStarting[ExperimentQuery, ExperimentJob, F]] = {

    val jobId :: featuresTable :: metadata :: query :: feedback :: HNil =
      preparedQuery

    featuresService
      .featuresTable(featuresTable)
      .andThen { fts =>
        val featuresTableDescription = fts.table
        ExperimentJob
          .mkValid(jobId, query, featuresTableDescription, metadata, { a: AlgorithmSpec =>
            algorithmLookup(a.code)
          })
          .map(job => JobStarting(job, fts, feedback))
      }
  }

  private def prepareQuery[Q <: Query](
      variablesMetaService: VariablesMetaRepository[F],
      jobsConfiguration: JobsConfiguration,
      query: Q
  ): F[Validation[PreparedQuery[Q]]] = {
    logger.debug("Prepare query")

    val jobId         = UUID.randomUUID().toString
    val featuresTable = query.targetTable.getOrElse(jobsConfiguration.defaultFeaturesTable)

    def missingVariablesMessage(variableType: String, missing: FeatureIdentifiers): String = {
      val missingFields = missing.map(_.id).mkString_(",")
      val plural        = if (missing.length > 1) "s" else ""
      s"$variableType$plural $missingFields do not exist in node ${jobsConfiguration.node} and table ${featuresTable.name}"
    }

    def prepareFeedback(variableType: String,
                        requestedVars: FeatureIdentifiers,
                        existingVars: FeatureIdentifiers): UserFeedbacks =
      requestedVars
        .diff(existingVars)
        .toNel
        .fold[UserFeedbacks](Nil)(
          missing => {
            val missingMsg = missingVariablesMessage(variableType, missing.toList)
            List(UserInfo(missingMsg))
          }
        )

    // Fetch the metadata for variables
    variablesMetaService.get(featuresTable).map { variablesMetaO =>
      if (variablesMetaO.isDefined)
        logger.debug(s"Found variables meta for table $featuresTable")
      else
        logger.warn(s"Could not find variables meta for table $featuresTable")

      val variablesMeta: Validation[VariablesMeta] = Validated.fromOption(
        variablesMetaO,
        NonEmptyList(s"Cannot find metadata for table ${featuresTable.toString}", Nil)
      )

      variablesMeta
        .andThen { vars =>
          def filterExisting(requestedVars: FeatureIdentifiers): List[VariableId] =
            vars
              .filterVariables { v: VariableId =>
                requestedVars.contains(v)
              }
              .map(_.toId)

          def findMissing(
              requestedVars: FeatureIdentifiers
          ): Option[NonEmptyList[FeatureIdentifier]] =
            requestedVars.diff(filterExisting(requestedVars)).toNel

          def ensureVariablesExists(variableType: String, requestedVars: FeatureIdentifiers)
            : Validation[FeatureIdentifiers] =
            findMissing(requestedVars).fold(requestedVars.validNel[String])(
              missing =>
                missingVariablesMessage(variableType, missing.toList).invalidNel[FeatureIdentifiers]
            )

          logger.debug("Check target variable")

          // Check the target variable
          ensureVariablesExists("Variable", query.variables)
            .andThen { _ =>
              logger.debug("Check covariable")
              // Check covariables
              if (query.covariablesMustExist)
                ensureVariablesExists("Covariable", query.covariables)
                  .map(covars => (covars, List.empty[UserFeedback]))
              else {
                val existingCovars = filterExisting(query.covariables)
                val covariablesFeedback =
                  prepareFeedback("Covariable", query.covariables, existingCovars)
                if (query.covariables.nonEmpty && existingCovars.isEmpty)
                  covariablesFeedback.map(_.msg).mkString(",").invalidNel
                else
                  (existingCovars, covariablesFeedback).validNel[String]
              }
            }
            .andThen {
              // Check groupings
              case (existingCovariables, covariablesFeedback) =>
                logger.debug("Check groupings")
                val existingGroupings = filterExisting(query.grouping)
                val groupingsFeedback =
                  prepareFeedback("Grouping", query.grouping, existingGroupings)
                val combinedFeedback = covariablesFeedback ++ groupingsFeedback
                if (query.grouping.nonEmpty && existingGroupings.isEmpty)
                  groupingsFeedback.map(_.msg).mkString(",").invalidNel
                else
                  (existingCovariables, existingGroupings, combinedFeedback).validNel[String]
            }
            .andThen {
              // Update query
              case (existingCovariables, existingGroupings, existanceFeedback) =>
                logger.debug("Update query")
                // TODO: looks like a good use case for lenses
                val updatedQuery: Q = query match {
                  case q: MiningQuery =>
                    q.copy(covariables = existingCovariables,
                            grouping = existingGroupings,
                            targetTable = Some(featuresTable))
                      .asInstanceOf[Q]
                  case q: ExperimentQuery =>
                    q.copy(covariables = existingCovariables,
                            grouping = existingGroupings,
                            targetTable = Some(featuresTable))
                      .asInstanceOf[Q]
                }

                (updatedQuery, existanceFeedback).validNel[String]
            }
        }
        .andThen {
          case (q, feedback) =>
            variablesMeta
              .andThen { meta =>
                // Get the metadata associated with the variables
                logger.debug("Get metadata associated with the variables")
                meta.selectVariables(q.allVars)
              }
              .map { m =>
                // Build the PreparedQuery
                logger.debug("Build prepared query")
                jobId :: featuresTable :: m :: q :: feedback :: HNil
              }
        }
    }
  }

  private def validateQuery[Q <: Query](
      preparedQuery: PreparedQuery[Q],
      featuresService: FeaturesService[F]
  ): F[Validation[PreparedQuery[Q]]] = {
    logger.debug("Validate query")

    val _ :: featuresTable :: _ :: query :: _ :: HNil = preparedQuery

    val table = query.targetTable.getOrElse(featuresTable)
    logger.debug(s"Validate query on table $table")

    val validTableService: Validation[FeaturesTableService[F]] =
      featuresService.featuresTable(table)

    logger.debug(s"Table service found? ${validTableService.isValid}")
    if (validTableService.isInvalid) {
      logger.warn(s"Invalid table $table requested by query $query: $validTableService")
    }

    validTableService
      .map { tableService =>
        logger.debug(s"Count number of rows for filter ${query.filters}")
        for {
          numRows <- tableService.count(query.filters)
          hasData = if (numRows > 0) {
            logger.debug(s"Found $numRows")
            preparedQuery.validNel[String]
          } else {
            logger.warn(s"Found no data in table $table matching filters ${query.filters}")
            s"No data in table $table matching filters".invalidNel[PreparedQuery[Q]]
          }
        } yield hasData

      }
      .fold(toInvalidF[PreparedQuery[Q]], f => f)

  }

  private def toInvalidF[A](err: NonEmptyList[String]): F[Validation[A]] =
    Monad[F].pure(err.invalid[A])

}
