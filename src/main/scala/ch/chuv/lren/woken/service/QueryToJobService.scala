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
import cats.data.NonEmptyList._
import cats.data.Validated._
import cats.effect.{ Async, Effect }
import cats.implicits._
import ch.chuv.lren.woken.config.JobsConfiguration
import ch.chuv.lren.woken.core.ExperimentActor
import ch.chuv.lren.woken.core.features.Queries
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.{ Validation, lift }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import com.typesafe.scalalogging.LazyLogging
import shapeless.{ ::, HNil }

import scala.language.higherKinds

/**
  * Transform incoming mining and experiment queries into jobs
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
trait QueryToJobService[F[_]] {

  def miningQuery2Job(query: MiningQuery): F[Validation[(Job, UserFeedbacks)]]

  def experimentQuery2Job(query: ExperimentQuery): F[Validation[(Job, UserFeedbacks)]]

}

object QueryToJobService extends LazyLogging {

  // LATER Scala 3 - use opaque types
  type JobId         = String
  type FeaturesDb    = String
  type FeaturesTable = String
  type PreparedQuery[Q <: Query] =
    JobId :: FeaturesDb :: FeaturesTable :: List[VariableMetaData] :: Q :: UserFeedbacks :: HNil

  def apply[F[_]: Effect](
      featuresService: FeaturesService[F],
      variablesMetaService: VariablesMetaService[F],
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  ): QueryToJobService[F] =
    new QueryToJobServiceImpl[F](
      featuresService: FeaturesService[F],
      variablesMetaService: VariablesMetaService[F],
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
    )
}

class QueryToJobServiceImpl[F[_]: Effect](
    featuresService: FeaturesService[F],
    variablesMetaService: VariablesMetaService[F],
    jobsConfiguration: JobsConfiguration,
    algorithmLookup: String => Validation[AlgorithmDefinition]
) extends QueryToJobService[F] {

  import QueryToJobService._

  override def miningQuery2Job(query: MiningQuery): F[Validation[(Job, UserFeedbacks)]] =
    for {
      preparedQuery <- prepareQuery(variablesMetaService, jobsConfiguration, query)
      validatedQuery <- preparedQuery.fold(
        toInvalidF[PreparedQuery[MiningQuery]],
        pq => validateQuery(pq, featuresService)
      )
    } yield validatedQuery.andThen(q => createValidationOrMiningJob(q, algorithmLookup))

  private[this] def createValidationOrMiningJob(
      preparedQuery: PreparedQuery[MiningQuery],
      algorithmLookup: String => Validation[AlgorithmDefinition]
  ): Validation[(Job, UserFeedbacks)] = {
    val jobId :: featuresDb :: featuresTable :: metadata :: query :: feedback :: HNil =
      preparedQuery

    def createMiningJob(mt: List[VariableMetaData],
                        q: MiningQuery,
                        ad: AlgorithmDefinition): DockerJob = {
      val featuresQuery = q.filterDatasets
        .filterNulls(ad.variablesCanBeNull, ad.covariablesCanBeNull)
        .features(featuresTable)

      DockerJob(jobId, featuresDb, featuresQuery, q.algorithm, ad, metadata = mt)
    }

    val job = query.algorithm.code match {
      case ValidationJob.algorithmCode =>
        ValidationJob(jobId = jobId,
                      inputDb = featuresDb,
                      inputTable = featuresTable,
                      query = query,
                      metadata = metadata).validNel[String]

      case code =>
        algorithmLookup(code).map(algorithm => createMiningJob(metadata, query, algorithm))
    }

    job.map(_ -> feedback)
  }

  override def experimentQuery2Job(query: ExperimentQuery): F[Validation[(Job, UserFeedbacks)]] =
    for {
      preparedQuery <- prepareQuery(variablesMetaService, jobsConfiguration, query)
      validatedQuery <- preparedQuery.fold(
        toInvalidF[PreparedQuery[ExperimentQuery]],
        pq => validateQuery(pq, featuresService)
      )
      // TODO: query for experiment should filter for nulls if one of the algorithms require it
    } yield validatedQuery.andThen(q => createExperimentJob(q, algorithmLookup))

  private[this] def createExperimentJob(
      preparedQuery: PreparedQuery[ExperimentQuery],
      algorithmLookup: String => Validation[AlgorithmDefinition]
  ): Validation[(Job, UserFeedbacks)] = {

    val jobId :: featuresDb :: featuresTable :: metadata :: query :: feedback :: HNil =
      preparedQuery

    def createJob(mt: List[VariableMetaData],
                  q: ExperimentQuery,
                  algorithms: Map[AlgorithmSpec, AlgorithmDefinition]) =
      ExperimentActor.Job(jobId,
                          featuresDb,
                          featuresTable,
                          q,
                          metadata = mt,
                          algorithms = algorithms)

    query.algorithms
      .map { algorithm =>
        (lift(algorithm), algorithmLookup(algorithm.code)) mapN Tuple2.apply
      }
      .sequence[Validation, (AlgorithmSpec, AlgorithmDefinition)]
      .map(_.toMap)
      .map(algorithms => (createJob(metadata, query, algorithms), feedback))
  }

  private def prepareQuery[Q <: Query](
      variablesMetaService: VariablesMetaService[F],
      jobsConfiguration: JobsConfiguration,
      query: Q
  ): F[Validation[PreparedQuery[Q]]] = {

    val jobId         = UUID.randomUUID().toString
    val featuresDb    = jobsConfiguration.featuresDb
    val featuresTable = query.targetTable.getOrElse(jobsConfiguration.featuresTable)
    val metadataKey   = query.targetTable.getOrElse(jobsConfiguration.metadataKeyForFeaturesTable)

    def prepareFeedback(oldVars: FeatureIdentifiers,
                        existingVars: FeatureIdentifiers): UserFeedbacks =
      oldVars
        .intersect(existingVars)
        .toNel
        .fold[UserFeedbacks](Nil)(
          missing => {
            val missingFields = missing.map(Queries.toField).mkString_("", ",", "")
            List(UserInfo(s"Missing variables $missingFields"))
          }
        )

    variablesMetaService.get(metadataKey).map { variablesMetaO =>
      val variablesMeta: Validation[VariablesMeta] = Validated.fromOption(
        variablesMetaO,
        NonEmptyList(s"Cannot find metadata for table $metadataKey", Nil)
      )

      val validatedQueryWithFeedback: Validation[(Q, UserFeedbacks)] = variablesMeta.map { v =>
        if (query.covariablesMustExist)
          // TODO: check that the covariables do exist, fail early otherwise
          (query, Nil)
        else {

          // Take only the covariables (and groupings) known to exist on the target table
          val existingDbCovariables = v.filterVariables(query.dbCovariables.contains).map(_.code)
          val existingCovariables = query.covariables.filter { covar =>
            existingDbCovariables.contains(Queries.toField(covar))
          }
          val covariablesFeedback = prepareFeedback(query.covariables, existingCovariables)

          val existingDbGroupings = v.filterVariables(query.dbGrouping.contains).map(_.code)
          val existingGroupings = query.grouping.filter { grouping =>
            existingDbGroupings.contains(Queries.toField(grouping))
          }
          val groupingsFeedback = prepareFeedback(query.grouping, existingGroupings)

          val feedback: UserFeedbacks = covariablesFeedback ++ groupingsFeedback

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

          (updatedQuery, feedback)
        }
      }

      val validatedQuery: Validation[Q] = validatedQueryWithFeedback.map(_._1)

      val mq: Validation[(VariablesMeta, Q)] =
        (variablesMeta, validatedQuery) mapN Tuple2.apply

      val metadata: Validation[List[VariableMetaData]] = mq.andThen {
        case (v, q) =>
          v.selectVariables(q.dbAllVars)
      }

      val feedback: UserFeedbacks = validatedQueryWithFeedback.map(_._2).getOrElse(Nil)

      (metadata, validatedQuery) mapN Tuple2.apply map {
        case (m, q) =>
          jobId :: featuresDb :: featuresTable :: m :: q :: feedback :: HNil
      }
    }
  }

  private def validateQuery[Q <: Query](
      preparedQuery: PreparedQuery[Q],
      featuresService: FeaturesService[F]
  ): F[Validation[PreparedQuery[Q]]] = {

    val _ :: _ :: featuresTable :: _ :: query :: _ :: HNil = preparedQuery

    val table = query.targetTable.getOrElse(featuresTable)
    // TODO: Add targetSchema to query or schema to configuration or both, use it here instead of None
    val validTableService: Validation[FeaturesTableService[F]] =
      featuresService
        .featuresTable(None, table)

    validTableService
      .map { tableService =>
        for {
          numRows <- tableService.count(query.filters)
          hasData = if (numRows > 0) preparedQuery.validNel[String]
          else s"No data in table $table matching filters".invalidNel[PreparedQuery[Q]]
        } yield hasData

      }
      .fold(toInvalidF[PreparedQuery[Q]], f => f)

  }

  private def toInvalidF[A](err: NonEmptyList[String]): F[Validation[A]] =
    Async[F].delay(err.invalid[A])

}
