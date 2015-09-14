package models

import spray.json.DefaultJsonProtocol

// http://www.cakesolutions.net/teamblogs/2012/11/30/spray-json-and-adts

trait CWLProcess {
  def id: Option[String] = None
  def label: String
  def description: Option[String] = None
  def inputs: List[InputParameter]
  def outputs: List[OutputParameter]
  def requirement: List[ProcessRequirement] = List()
  def hint: List[Hint] = List()
}

sealed trait DataType

object PrimitiveTypes {

  /** no value */
  case object Null extends DataType
  /** a binary value */
  case object Boolean extends DataType
  /** 32 -bit signed integer */
  case object Int extends DataType
  /** 64 -bit signed integer */
  case object Long extends DataType
  /** single precision ( 32 -bit) IEEE 754 floating - point number */
  case object Float extends DataType
  /** double precision ( 64 -bit) IEEE 754 floating - point number */
  case object Double extends DataType
  /** sequence of uninterpreted 8 -bit unsigned bytes */
  case object Bytes extends DataType
  /** Unicode character sequence */
  case object String extends DataType
}

object ComplexType {
//  record: An object with one or more fields defined by name and type
//  enum: A value from a finite set of symbolic values
//  array: An ordered sequence of values
//  map: An unordered collection of key/value pairs
}


sealed trait Parameter {
  /** Specify valid types of data that may be assigned to this parameter */
  def tpe: List[DataType] = List()

  /** A short, human-readable label of this parameter object */
  def label: Option[String] = None

  /** A long, human-readable description of this parameter object */
  def description: Option[String] = None

  /** Currently only applies if type is File. A value of true indicates that the file is read or written sequentially without seeking.
    * An implementation may use this flag to indicate whether it is valid to stream file contents using a named pipe.
    * Default: false.
    */
  def streamable: Boolean = false

  def default: Option[Any] = None
}

trait InputParameter extends Parameter
trait OutputParameter extends Parameter

case class CommandInputParameter() extends InputParameter
case class CommandOutputParameter() extends OutputParameter

sealed trait ProcessRequirement {
  def clazz: String
}

/**
 * @param dockerPull Specify a Docker image to retrieve using docker pull.
 * @param dockerLoad Specify a HTTP URL from which to download a Docker image using docker load.
 * @param dockerFile Supply the contents of a Dockerfile which will be built using docker build.
 * @param dockerImport Provide HTTP URL to download and gunzip a Docker images using docker import.
 * @param dockerImageId The image id that will be used for docker run. May be a human-readable image name or the image
 *                      identifier hash. May be skipped if dockerPull is specified, in which case the dockerPull image
 *                      id must be used.
 * @param dockerOutputDirectory Set the designated output directory to a specific location inside the Docker container.
 */
final case class DockerRequirement(
                                  dockerPull: Option[String] = None, // TODO: should be dockerForcePull: boolean, redundant with dockerImageId
                                  dockerLoad: Option[String] = None,
                                  dockerFile: Option[String] = None,
                                  dockerImport: Option[String] = None,
                                  dockerImageId: Option[String] = None,
                                  dockerInputDirectory: Option[String] = None, // TODO: missing from spec
                                  dockerOutputDirectory: Option[String] = None
                                    ) extends ProcessRequirement {
  def clazz = "DockerRequirement"
}

final case class SubworkflowFeatureRequirement() extends ProcessRequirement {
  def clazz = "SubworkflowFeatureRequirement"
}
final case class CreateFileRequirement() extends ProcessRequirement {
  def clazz = "CreateFileRequirement"
}
final case class EnvVarRequirement() extends ProcessRequirement {
  def clazz = "EnvVarRequirement"
}
final case class ScatterFeatureRequirement() extends ProcessRequirement {
  def clazz = "ScatterFeatureRequirement"
}
final case class SchemaDefRequirement() extends ProcessRequirement {
  def clazz = "SchemaDefRequirement"
}
final case class ExpressionEngineRequirement() extends ProcessRequirement {
  def clazz = "ExpressionEngineRequirement"
}

case class Hint()

//case class CommandLineTool(
//                            override val id: Option[String] = None,
//                            override val label: String,
//                            override val description: Option[String] = None,
//                            override val inputs: List[CommandInputParameter],
//                            override val outputs: List[CommandOutputParameter],
//                            // TODO override val requirement: List[ProcessRequirement] = List(),
//                            override val hint: List[Hint] = List()
//                            ) extends models.CWLProcess
case class Process(
                            val id: Option[String] = None,
                            val label: String,
                            val description: Option[String] = None,
                            val inputs: List[CommandInputParameter],
                            val outputs: List[CommandOutputParameter],
                            // TODO override val requirement: List[ProcessRequirement] = List(),
                            val hint: List[Hint] = List()
                            )

object Process extends DefaultJsonProtocol {
  implicit val commandInputParameterFormat = jsonFormat0(CommandInputParameter.apply)
  implicit val commandOutputParameterFormat = jsonFormat0(CommandOutputParameter.apply)
  // implicit val dockerRequirementFormat = jsonFormat7(DockerRequirement.apply)
  implicit val hintFormat = jsonFormat0(Hint.apply)
  implicit val processFormat = jsonFormat6(Process.apply)

}

