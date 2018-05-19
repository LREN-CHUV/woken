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

package ch.chuv.lren.woken.core.model

import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.variables.{ VariableMetaData, variablesProtocol }
import com.typesafe.scalalogging.LazyLogging
import spray.json._
import variablesProtocol._

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
    metadata: List[VariableMetaData]
) extends Job
    with LazyLogging {

  def jobName: String =
    (dockerImage.replaceAll("^.*?/", "").takeWhile(_ != ':') + "_" + jobId)
      .replaceAll("[/.-]", "_")

  def environmentVariables: Map[String, String] =
    Map[String, String](
      "PARAM_query"       -> query.sql,
      "PARAM_variables"   -> query.dbVariables.mkString(","),
      "PARAM_covariables" -> query.dbCovariables.mkString(","),
      "PARAM_grouping"    -> query.dbGrouping.mkString(","),
      "PARAM_meta"        -> metadata.map(m => m.code -> m).toMap.toJson.compactPrint
    ) ++ algoParameters

  def dockerCommand: String = "compute"

  def dockerArguments: List[String] = algorithmSpec.step.fold(List[String]()) {
    step: ExecutionStep =>
      step.operation match {
        case Compute("compute")       => Nil
        case Compute("compute-local") => List("--mode", "intermediate")
        case Compute("compute-global") =>
          List("--mode", "aggregate", "--job-ids") ++
            algorithmSpec.parametersAsMap.getOrElse("_job_ids_", "").split(",")
        case unhandled =>
          logger.warn(s"Unhandled operation $unhandled")
          Nil
      }
  }

  private[this] def algoParameters: Map[String, String] = {
    val parameters = algorithmSpec.parametersAsMap.filterKeys(_ != "_job_ids_")
    parameters.map({ case (key, value) => ("MODEL_PARAM_" + key, value) })
  }

}
