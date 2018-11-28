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
import cats.effect.Effect
import cats.implicits._
import cats.syntax.traverse._
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
object QueryToJob extends LazyLogging {

  // LATER Scala 3 - use opaque types
  type JobId         = String
  type FeaturesDb    = String
  type FeaturesTable = String
  type PreparedQuery[Q <: Query] = JobId :: FeaturesDb :: FeaturesTable :: Validation[
    List[VariableMetaData]
  ] :: Validation[Q] :: UserFeedbacks :: HNil

  def miningQuery2Job[F[_]: Effect](
      featuresService: FeaturesService[F],
      variablesMetaService: VariablesMetaService[F],
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  )(query: MiningQuery): F[Validation[Job]] = {

    def createValidationOrMiningJob(
        preparedQuery: PreparedQuery[MiningQuery],
        algorithmLookup: String => Validation[AlgorithmDefinition]
    ): Validation[Job] = {
      val jobId :: featuresDb :: featuresTable :: metadata :: validatedQuery :: feedback :: HNil =
        preparedQuery

      def createMiningJob(mt: List[VariableMetaData],
                          q: MiningQuery,
                          ad: AlgorithmDefinition): DockerJob = {
        val featuresQuery = q.filterDatasets
          .filterNulls(ad.variablesCanBeNull, ad.covariablesCanBeNull)
          .features(featuresTable)

        DockerJob(jobId, featuresDb, featuresQuery, q.algorithm, ad, metadata = mt)
      }

      validatedQuery.andThen { query =>
        query.algorithm.code match {
          case ValidationJob.algorithmCode =>
            metadata.map { m =>
              ValidationJob(jobId = jobId,
                            inputDb = featuresDb,
                            inputTable = featuresTable,
                            query = query,
                            metadata = m)
            }
          case code =>
            val algorithm = algorithmLookup(code)
            (metadata, validatedQuery, algorithm) mapN createMiningJob
        }
      }
    }

    for {
      preparedQuery <- prepareQuery(variablesMetaService, jobsConfiguration, query)
      job = createValidationOrMiningJob(preparedQuery, algorithmLookup)
    } yield job
  }

  def experimentQuery2Job[F[_]: Effect](
      variablesMetaService: VariablesMetaService[F],
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  )(query: ExperimentQuery): F[Validation[Job]] =
    for {
      preparedQuery <- prepareQuery(variablesMetaService, jobsConfiguration, query)
      job = createExperimentJob(preparedQuery, algorithmLookup)
    } yield job

  private[this] def createExperimentJob(
      preparedQuery: PreparedQuery[ExperimentQuery],
      algorithmLookup: String => Validation[AlgorithmDefinition]
  ): Validation[ExperimentActor.Job] = {

    val jobId :: featuresDb :: featuresTable :: metadata :: validatedQuery :: feedback :: HNil =
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

    validatedQuery.andThen { query =>
      val validatedAlgorithms: Validation[Map[AlgorithmSpec, AlgorithmDefinition]] =
        query.algorithms
          .map { algorithm =>
            (lift(algorithm), algorithmLookup(algorithm.code)) mapN Tuple2.apply
          }
          .traverse[Validation, (AlgorithmSpec, AlgorithmDefinition)](identity)
          .map(_.toMap)

      (metadata, validatedQuery, validatedAlgorithms) mapN createJob
    }
  }

  private def prepareQuery[Q <: Query, F[_]: Effect](
      variablesMetaService: VariablesMetaService[F],
      jobsConfiguration: JobsConfiguration,
      query: Q
  ): F[PreparedQuery[Q]] = {

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

      val m: Validation[(VariablesMeta, Q)] =
        (variablesMeta, validatedQuery) mapN Tuple2.apply

      val metadata: Validation[List[VariableMetaData]] = m.andThen {
        case (v, q) =>
          v.selectVariables(q.dbAllVars)
      }

      val feedback: UserFeedbacks = validatedQueryWithFeedback.map(_._2).getOrElse(Nil)

      jobId :: featuresDb :: featuresTable :: metadata :: validatedQuery :: feedback :: HNil
    }
  }

  private def validateQuery[Q <: Query, F[_]: Effect](
      preparedQuery: PreparedQuery[Q],
      featuresService: FeaturesService[F]
  ): F[PreparedQuery[Q]] = {

    val jobId :: featuresDb :: featuresTable :: metadata :: validatedQuery :: feedback :: HNil =
      preparedQuery

    val validTableService: Validation[FeaturesTableService[F]] = validatedQuery.andThen(
      query =>
        featuresService
          .featuresTable(query.targetTable.getOrElse(featuresTable))
          .toValidatedNel[String]
    )

    // TODO: use instead validTableService.liftTo[F]

    val ts: F[FeaturesTableService[F]] = validTableService.fold(
      err => Effect[F].raiseError(new IllegalArgumentException(err.mkString_("", ",", ""))),
      Effect[F].delay _
    )

    for {
      tableService <- ts
       _ <- tableService.count

    }

    val v = (validTableService, validatedQuery) mapN Tuple2.apply



    validTableService.andThen(
      tableService =>
        validatedQuery.fold(Effect[F].delay(_))(q => {

          val v: F[Validation[Q]] = for {
            numRows <- tableService.count(q.filters)
            hasData = if (numRows > 0) validatedQuery else "".invalidNel[Q]
          } yield hasData

        })
    )

  }

}
