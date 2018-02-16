/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.api

import java.util.UUID

import cats.data.Validated
import eu.hbp.mip.woken.backends.DockerJob
import ch.chuv.lren.woken.messages.query._
import eu.hbp.mip.woken.config.{ AlgorithmDefinition, JobsConfiguration }
import eu.hbp.mip.woken.core.ExperimentActor
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.service.VariablesMetaService
import eu.hbp.mip.woken.core.features.Queries._
import eu.hbp.mip.woken.core.model.VariablesMeta
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.lift
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
      val featuresQuery = query.features(featuresTable, !al.supportsNullValues, None)
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
      ExperimentActor.Job(jobId, featuresDb, featuresTable, query, metadata = mt).validNel[String]
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
      variablesMeta.andThen(v => {
        val vars          = query.dbAllVars
        val variablesMeta = v.selectVariablesMeta(vars.contains)
        if (variablesMeta.lengthCompare(vars.size) != 0) {
          val missingVars = vars.diff(variablesMeta.map(_.code))
          logger.warn(
            s"Could not find all variables: ${variablesMeta.size} out of ${vars.size}. Missing ${missingVars
              .mkString(",")}"
          )
        }
        lift(variablesMeta)
      })

    jobId :: featuresDb :: featuresTable :: metadata :: HNil
  }

}
