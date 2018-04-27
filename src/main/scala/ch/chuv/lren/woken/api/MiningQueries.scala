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

package ch.chuv.lren.woken.api

import java.util.UUID

import cats.data.Validated
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.config.{ AlgorithmDefinition, JobsConfiguration }
import ch.chuv.lren.woken.core.ExperimentActor
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.service.VariablesMetaService
import ch.chuv.lren.woken.core.features.Queries._
import ch.chuv.lren.woken.core.model.VariablesMeta
import cats.data._
import cats.implicits._
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import com.typesafe.scalalogging.LazyLogging
import shapeless.{ ::, HNil }

/**
  * Transform incoming mining and experiment queries into jobs
  */
object MiningQueries extends LazyLogging {

  def miningQuery2Job(
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  )(query: MiningQuery): Validation[DockerJob] = {

    val jobId :: featuresDb :: featuresTable :: metadata :: HNil =
      prepareQuery(variablesMetaService, jobsConfiguration, query)
    val algorithm = algorithmLookup(query.algorithm.code)

    def createJob(mt: List[VariableMetaData], al: AlgorithmDefinition) = {
      val featuresQuery = query.filterDatasets
        .filterNulls(al.variablesCanBeNull, al.covariablesCanBeNull)
        .features(featuresTable, None)

      DockerJob(jobId, al.dockerImage, featuresDb, featuresQuery, query.algorithm, metadata = mt)
    }

    (metadata, algorithm) mapN createJob
  }

  def experimentQuery2Job(
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration
  )(query: ExperimentQuery): Validation[ExperimentActor.Job] = {

    val jobId :: featuresDb :: featuresTable :: metadata :: HNil =
      prepareQuery(variablesMetaService, jobsConfiguration, query)

    metadata.andThen { mt: List[VariableMetaData] =>
      ExperimentActor
        .Job(jobId, featuresDb, featuresTable, query.filterDatasets, metadata = mt)
        .validNel[String]
    }
  }

  private def prepareQuery(
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration,
      query: Query
  ): String :: String :: String :: Validation[List[VariableMetaData]] :: HNil = {
    val jobId         = UUID.randomUUID().toString
    val featuresDb    = jobsConfiguration.featuresDb
    val featuresTable = query.targetTable.getOrElse(jobsConfiguration.featuresTable)
    val metadataKey   = query.targetTable.getOrElse(jobsConfiguration.metadataKeyForFeaturesTable)
    val variablesMeta: Validation[VariablesMeta] = Validated.fromOption(
      variablesMetaService.get(metadataKey),
      NonEmptyList(s"Cannot find metadata for table $metadataKey", Nil)
    )
    val metadata: Validation[List[VariableMetaData]] =
      variablesMeta.andThen(v => v.selectVariables(query.dbAllVars))

    jobId :: featuresDb :: featuresTable :: metadata :: HNil
  }

}
