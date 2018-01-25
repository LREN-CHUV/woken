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

package eu.hbp.mip.woken.backends

import eu.hbp.mip.woken.core.model.Queries._
import eu.hbp.mip.woken.messages.datasets.DatasetsProtocol
import eu.hbp.mip.woken.messages.query._
import eu.hbp.mip.woken.messages.query.filters.QueryFiltersProtocol
import eu.hbp.mip.woken.messages.variables.VariablesProtocol
import spray.json.{ JsObject, JsonFormat, RootJsonFormat }

/**
  * Definition of a computation using an algorithm packaged as a Docker container.
  *
  * @param jobId Id of the job. Must be unique
  * @param dockerImage Name of the Docker image to use. Include the version to ensure reproducibility
  * @param inputDb Name of the input database
  * @param inputTable Name of the input table
  * @param query The original query
  * @param metadata Metadata associated with each field used in the query
  */
case class DockerJob(
    jobId: String,
    dockerImage: String,
    inputDb: String,
    inputTable: String,
    query: MiningQuery,
    metadata: JsObject,
    shadowOffset: Option[QueryOffset] = None
) {

  def jobName: String =
    (dockerImage.replaceAll("^.*?/", "").takeWhile(_ != ':') + "_" + jobId)
      .replaceAll("[/.-]", "_")

  def dockerParameters: Map[String, String] =
    Map[String, String](
      "PARAM_query"       -> FeaturesHelper.buildQueryFeaturesSql(inputTable, query, shadowOffset),
      "PARAM_variables"   -> query.dbVariables.mkString(","),
      "PARAM_covariables" -> query.dbCovariables.mkString(","),
      "PARAM_grouping"    -> query.dbGrouping.mkString(","),
      "PARAM_meta"        -> metadata.compactPrint
    ) ++ algoParameters

  private[this] def algoParameters: Map[String, String] = {
    val parameters = query.algorithm.parametersAsMap
    parameters.map({ case (key, value) => ("MODEL_PARAM_" + key, value) }) ++
    parameters.map({ case (key, value) => ("PARAM_MODEL_" + key, value) })
  }

}

object DockerJob
    extends QueryProtocol
    with DatasetsProtocol
    with VariablesProtocol
    with QueryFiltersProtocol {

  implicit val formatQueryOffset: JsonFormat[QueryOffset] = jsonFormat2(QueryOffset.apply)
  implicit val formatDockerJob: RootJsonFormat[DockerJob] = jsonFormat7(DockerJob.apply)

}
