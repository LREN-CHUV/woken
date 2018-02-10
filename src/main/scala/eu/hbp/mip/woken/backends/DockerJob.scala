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

import eu.hbp.mip.woken.core.features.FeaturesQuery
import eu.hbp.mip.woken.messages.query._
import spray.json.JsObject

/**
  * Definition of a computation using an algorithm packaged as a Docker container.
  *
  * @param jobId Id of the job. Must be unique
  * @param dockerImage Name of the Docker image to use. Include the version to ensure reproducibility
  * @param inputDb Name of the input database
  * @param query A representation of the query selecting features
  * @param algorithmSpec Specifications for the algorithm. We use only the parameters here, the algorithm having already been used to select the Docker image to execute.
  * @param metadata Metadata associated with each field used in the query
  */
case class DockerJob(
    jobId: String,
    dockerImage: String,
    inputDb: String,
    query: FeaturesQuery,
    algorithmSpec: AlgorithmSpec,
    metadata: JsObject
) {

  def jobName: String =
    (dockerImage.replaceAll("^.*?/", "").takeWhile(_ != ':') + "_" + jobId)
      .replaceAll("[/.-]", "_")

  //
  def dockerParameters: Map[String, String] =
    Map[String, String](
      "PARAM_query"       -> query.query,
      "PARAM_variables"   -> query.dbVariables.mkString(","),
      "PARAM_covariables" -> query.dbCovariables.mkString(","),
      "PARAM_grouping"    -> query.dbGrouping.mkString(","),
      "PARAM_meta"        -> metadata.compactPrint
    ) ++ algoParameters

  private[this] def algoParameters: Map[String, String] = {
    val parameters = algorithmSpec.parametersAsMap
    parameters.map({ case (key, value) => ("MODEL_PARAM_" + key, value) }) ++
    parameters.map({ case (key, value) => ("PARAM_MODEL_" + key, value) })
  }

}
