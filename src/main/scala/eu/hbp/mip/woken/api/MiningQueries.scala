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

import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.config.{ AlgorithmDefinition, JobsConfiguration }
import eu.hbp.mip.woken.core.ExperimentActor
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.service.VariablesMetaService
import eu.hbp.mip.woken.core.model.Queries._

import cats.implicits._

/**
  * Transform incoming mining and experiment queries into jobs
  */
object MiningQueries {

  def miningQuery2job(
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration,
      algorithmLookup: String => Validation[AlgorithmDefinition]
  )(query: MiningQuery): Validation[DockerJob] = {

    val jobId         = UUID.randomUUID().toString
    val featuresDb    = jobsConfiguration.featuresDb
    val featuresTable = jobsConfiguration.featuresTable
    val metadata      = variablesMetaService.get(featuresTable).get.getMetaData(query.dbAllVars)

    algorithmLookup(query.algorithm.code).andThen { algo =>
      DockerJob(jobId, algo.dockerImage, featuresDb, featuresTable, query, metadata = metadata).validNel
    }
  }

  def experimentQuery2job(
      variablesMetaService: VariablesMetaService,
      jobsConfiguration: JobsConfiguration
  )(query: ExperimentQuery): ExperimentActor.Job = {

    val jobId         = UUID.randomUUID().toString
    val featuresDb    = jobsConfiguration.featuresDb
    val featuresTable = jobsConfiguration.featuresTable
    val metadata      = variablesMetaService.get(featuresTable).get.getMetaData(query.dbAllVars)

    ExperimentActor.Job(jobId, featuresDb, featuresTable, query, metadata = metadata)
  }

}
