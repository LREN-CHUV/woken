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

package eu.hbp.mip.woken.backends.chronos

import spray.json.{
  DefaultJsonProtocol,
  DeserializationException,
  JsString,
  JsValue,
  RootJsonFormat
}

// Adapted from https://github.com/mesos/chronos/blob/v3.0.2/src/main/scala/org/apache/mesos/chronos/scheduler/jobs/Containers.scala
// and https://github.com/mesos/chronos/blob/v3.0.2/src/main/scala/org/apache/mesos/chronos/scheduler/jobs/Jobs.scala

object VolumeMode extends Enumeration {
  type VolumeMode = Value

  // read-write and read-only.
  val RW, RO = Value
}

object NetworkMode extends Enumeration {
  type NetworkMode = Value

  // Bridged, Host and USER
  val BRIDGE, HOST, USER = Value
}

object ContainerType extends Enumeration {
  type ContainerType = Value

  // Docker, Mesos
  val DOCKER, MESOS = Value
}

object ProtocolType extends Enumeration {
  type ProtocolType = Value

  val IPv4, IPv6 = Value
}

import eu.hbp.mip.woken.backends.chronos.VolumeMode.VolumeMode
import eu.hbp.mip.woken.backends.chronos.NetworkMode.NetworkMode
import eu.hbp.mip.woken.backends.chronos.ContainerType.ContainerType
import eu.hbp.mip.woken.backends.chronos.ProtocolType.ProtocolType

/**
  * Represents an environment variable definition for the job
  */
case class Label(
    key: String,
    value: String
)

case class ExternalVolume(
    name: String,
    provider: String,
    options: List[Parameter]
)

case class Volume(
    containerPath: String,
    hostPath: Option[String],
    mode: Option[VolumeMode],
    external: Option[ExternalVolume]
)

case class PortMapping(hostPort: Int, containerPort: Int, protocol: Option[String])

case class Network(name: String,
                   protocol: Option[ProtocolType] = None,
                   labels: List[Label] = Nil,
                   portMappings: List[PortMapping] = Nil)

case class Container(
    `type`: ContainerType,
    image: String,
    forcePullImage: Boolean = false,
    parameters: List[Parameter] = Nil,
    volumes: List[Volume] = Nil,
    network: NetworkMode = NetworkMode.HOST,
    networkInfos: List[Network] = Nil
)

/**
  * Represents an environment variable definition for the job
  */
case class Parameter(
    key: String,
    value: String
)

case class EnvironmentVariable(
    name: String,
    value: String
)

case class Uri(
    uri: String
)

/**
  * A job to submit to Chronos via its REST API.
  *
  * @param name The job name. Must match the following regular expression: ([\w\.-]+)
  * @param description Description of job
  * @param command The actual command that will be executed by Chronos
  * @param arguments Arguments to pass to the command. Ignored if shell is true
  * @param shell If true, Mesos will execute command by running /bin/sh -c [command] and will ignore arguments. If false, command will be treated as the filename of an executable and arguments will be the arguments passed. If this is a Docker job and shell is true, the entrypoint of the container will be overridden with /bin/sh -c
  * @param schedule The scheduling for the job, in ISO 8601 format
  * @param epsilon If Chronos misses the scheduled run time for any reason, it will still run the job if the time is within this interval. Epsilon must be formatted like an ISO 8601 Duration
  * @param runAsUser Mesos will run the job as this user, if specified
  * @param container This contains the subfields for the Docker container: type (required), image (required), forcePullImage (optional), network (optional), and volumes (optional)
  * @param cpus Amount of Mesos CPUs for this job
  * @param mem Amount of Mesos Memory (in MB) for this job
  * @param disk Amount of Mesos disk (in MB) for this job
  * @param owner The email address of the person responsible for the job
  * @param environmentVariables An array of environment variables passed to the Mesos executor. For Docker containers, these are also passed to Docker using the -e flag
  */
case class ChronosJob(
    name: String,
    description: Option[String] = None,
    command: String,
    arguments: List[String] = Nil,
    shell: Boolean = true,
    schedule: String,
    epsilon: Option[String] = None,
    highPriority: Boolean = false,
    executor: Option[String] = None,
    executorFlags: Option[String] = None,
    runAsUser: Option[String] = None,
    container: Option[Container],
    cpus: Option[Double] = None,
    disk: Option[Double] = None,
    mem: Option[Double] = None,
    disabled: Boolean = false,
    owner: Option[String] = None,
    ownerName: Option[String] = None,
    environmentVariables: List[EnvironmentVariable],
    retries: Int = 2
    // dataProcessingJobType: Boolean = false,
    // scheduleTimeZone: Option[String] = None
    // concurrent: Boolean = false,
    // successCount: Option[Long] = None,
    // errorCount: Option[Long] = None,
    // lastSuccess: Option[String] = None,
    // lastError: Option[String] = None,
    // softError: Boolean = false,
    // errorsSinceLastSuccess: Option[Long] = None,
    // taskInfoData: Option[String] = None,
    // fetch: List[Fetch] = List()
    // constraints: List[Constraint] = List()
)

/**
  * Serialize ChronosJob in the Json format required by Chronos
  */
object ChronosJob extends DefaultJsonProtocol {

  class EnumJsonConverter[T <: scala.Enumeration](enu: T) extends RootJsonFormat[T#Value] {
    override def write(obj: T#Value): JsValue = JsString(obj.toString)

    override def read(json: JsValue): T#Value =
      json match {
        case JsString(txt) => enu.withName(txt)
        case somethingElse =>
          throw DeserializationException(
            s"Expected a value from enum $enu instead of $somethingElse"
          )
      }
  }

  implicit val volumeModeFormat: EnumJsonConverter[VolumeMode.type] =
    new EnumJsonConverter[VolumeMode.type](VolumeMode)
  implicit val NetworkModeFormat: EnumJsonConverter[NetworkMode.type] =
    new EnumJsonConverter[NetworkMode.type](NetworkMode)
  implicit val ContainerTypeFormat: EnumJsonConverter[ContainerType.type] =
    new EnumJsonConverter[ContainerType.type](ContainerType)
  implicit val ProtocolTypeFormat: EnumJsonConverter[ProtocolType.type] =
    new EnumJsonConverter[ProtocolType.type](ProtocolType)

  implicit val labelFormat: RootJsonFormat[Label]         = jsonFormat2(Label.apply)
  implicit val parameterFormat: RootJsonFormat[Parameter] = jsonFormat2(Parameter.apply)
  implicit val externalVolumeFormat: RootJsonFormat[ExternalVolume] = jsonFormat3(
    ExternalVolume.apply
  )
  implicit val volumeFormat: RootJsonFormat[Volume]           = jsonFormat4(Volume.apply)
  implicit val portMappingFormat: RootJsonFormat[PortMapping] = jsonFormat3(PortMapping.apply)
  implicit val networkFormat: RootJsonFormat[Network]         = jsonFormat4(Network.apply)
  implicit val containerFormat: RootJsonFormat[Container]     = jsonFormat7(Container.apply)
  implicit val environmentVariableFormat: RootJsonFormat[EnvironmentVariable] = jsonFormat2(
    EnvironmentVariable.apply
  )
  implicit val uriFormat: RootJsonFormat[Uri]               = jsonFormat1(Uri.apply)
  implicit val chronosJobFormat: RootJsonFormat[ChronosJob] = jsonFormat20(ChronosJob.apply)

}
