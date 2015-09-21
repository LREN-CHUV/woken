package api

import com.wordnik.swagger.annotations.{ApiModel, ApiModelProperty}
import spray.json.DefaultJsonProtocol

import scala.annotation.meta.field

@ApiModel(description = "Definition of a calculation")
case class JobDto(
  @(ApiModelProperty @field)(required = true, value = "Id of the request. Must be unique")
  requestId: String,

  @(ApiModelProperty @field)(required = true, value = "name of the Docker image to use. Include the version to ensure reproducibility")
  dockerImage: String,

  @(ApiModelProperty @field)(value = "name of the input database")
  inputDb: Option[String] = None,

  @(ApiModelProperty @field)(value = "name of the output database")
  outputDb: Option[String] = None,

  @(ApiModelProperty @field)(required = true, value = "additional parameters")
  parameters: Map[String, String] )

object JobDto extends DefaultJsonProtocol {
  implicit val jobDtoFormat = jsonFormat5(JobDto.apply)
}
