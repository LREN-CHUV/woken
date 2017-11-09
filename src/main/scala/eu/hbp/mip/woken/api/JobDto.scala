/*
 * Copyright 2017 LREN CHUV
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

import com.wordnik.swagger.annotations.{ ApiModel, ApiModelProperty }
import spray.json.DefaultJsonProtocol

import scala.annotation.meta.field

@ApiModel(description = "Definition of a calculation")
case class JobDto(
    @(ApiModelProperty @field)(required = true, value = "Id of the job. Must be unique")
    jobId: String,
    @(ApiModelProperty @field)(
      required = true,
      value = "name of the Docker image to use. Include the version to ensure reproducibility"
    )
    dockerImage: String,
    @(ApiModelProperty @field)(
      required = true,
      value =
        "name of the Docker image to use on Federation. Include the version to ensure reproducibility"
    )
    federationDockerImage: Option[String],
    @(ApiModelProperty @field)(
      value =
        "name of the job in Chronos. Must be unique. Default value is constructed from jobId and dockerImage"
    )
    jobName: Option[String],
    @(ApiModelProperty @field)(value = "name of the input database")
    inputDb: Option[String],
    @(ApiModelProperty @field)(required = true, value = "additional parameters")
    parameters: Map[String, String],
    @(ApiModelProperty @field)(required = false, value = "selected nodes")
    nodes: Option[Set[String]]
) {

  def jobNameResolved: String =
    jobName
      .getOrElse(dockerImage.replaceAll("^.*?/", "").takeWhile(_ != ':') + "_" + jobId)
      .replaceAll("[/.-]", "_")

}

object JobDto extends DefaultJsonProtocol {
  implicit val jobDtoFormat = jsonFormat7(JobDto.apply)
}
