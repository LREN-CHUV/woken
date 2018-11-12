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

import cats.data.Validated._
import cats.data.{ Validated, _ }
import cats.implicits._
import ch.chuv.lren.woken.config.JobsConfiguration
import ch.chuv.lren.woken.core.ExperimentActor
import ch.chuv.lren.woken.core.features.Queries
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.{ Validation, lift }
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import com.typesafe.scalalogging.LazyLogging
import shapeless.{ ::, HNil }

/**
  * Transform incoming mining and experiment queries into jobs
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
object QueryToJob extends LazyLogging {

  def miningQuery2Job(
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  )(query: MiningQuery): Validation[Job] = {

    val jobId :: featuresDb :: featuresTable :: metadata :: validatedQuery :: HNil =
      prepareQuery(variablesMetaService, jobsConfiguration, query)

    def createJob(mt: List[VariableMetaData], q: MiningQuery, ad: AlgorithmDefinition) = {
      val featuresQuery = q.filterDatasets
        .filterNulls(ad.variablesCanBeNull, ad.covariablesCanBeNull)
        .features(featuresTable)

      DockerJob(jobId, featuresDb, featuresQuery, query.algorithm, ad, metadata = mt)
    }

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
        (metadata, validatedQuery, algorithm) mapN createJob
    }

  }

  def experimentQuery2Job(
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  )(query: ExperimentQuery): Validation[ExperimentActor.Job] = {

    val jobId :: featuresDb :: featuresTable :: metadata :: validatedQuery :: HNil =
      prepareQuery(variablesMetaService, jobsConfiguration, query)

    def createJob(mt: List[VariableMetaData],
                  q: ExperimentQuery,
                  algorithms: Map[AlgorithmSpec, AlgorithmDefinition]) =
      ExperimentActor.Job(jobId,
                          featuresDb,
                          featuresTable,
                          q,
                          metadata = mt,
                          algorithms = algorithms)

    val validatedAlgorithms: Validation[Map[AlgorithmSpec, AlgorithmDefinition]] =
      query.algorithms
        .map { algorithm =>
          (lift(algorithm), algorithmLookup(algorithm.code)) mapN Tuple2.apply
        }
        .traverse[Validation, (AlgorithmSpec, AlgorithmDefinition)](identity)
        .map(_.toMap)

    (metadata, validatedQuery, validatedAlgorithms) mapN createJob

  }

  private def prepareQuery[Q <: Query](
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration,
      query: Q
  ): String :: String :: String :: Validation[List[VariableMetaData]] :: Validation[Q] :: HNil = {
    val jobId         = UUID.randomUUID().toString
    val featuresDb    = jobsConfiguration.featuresDb
    val featuresTable = query.targetTable.getOrElse(jobsConfiguration.featuresTable)
    val metadataKey   = query.targetTable.getOrElse(jobsConfiguration.metadataKeyForFeaturesTable)
    val variablesMeta: Validation[VariablesMeta] = Validated.fromOption(
      variablesMetaService.get(metadataKey).unsafeRunSync(),
      NonEmptyList(s"Cannot find metadata for table $metadataKey", Nil)
    )

    val validatedQuery: Validation[Q] = variablesMeta.map { v =>
      if (query.covariablesMustExist)
        query
      else {

        // Take only the covariables (and groupings) known to exist on the target table
        val existingDbCovariables = v.filterVariables(query.dbCovariables.contains).map(_.code)
        val existingCovariables = query.covariables.filter { covar =>
          existingDbCovariables.contains(Queries.toField(covar))
        }
        val existingDbGroupings = v.filterVariables(query.dbGrouping.contains).map(_.code)
        val existingGroupings = query.grouping.filter { grouping =>
          existingDbGroupings.contains(Queries.toField(grouping))
        }

        query match {
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
      }
    }

    val m: Validation[(VariablesMeta, Q)] =
      (variablesMeta, validatedQuery) mapN Tuple2.apply

    val metadata: Validation[List[VariableMetaData]] = m.andThen {
      case (v, q) =>
        v.selectVariables(q.dbAllVars)
    }

    jobId :: featuresDb :: featuresTable :: metadata :: validatedQuery :: HNil
  }

}
