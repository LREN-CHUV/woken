package eu.hbp.mip.woken.core.model

import spray.json.{RootJsonFormat, DefaultJsonProtocol}

case class Container(
  `type`: String,
  image: String,
  network: Option[String] = None,
  parameters: List[DockerParameter]
)

case class DockerParameter(
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

case class ChronosJob(
   schedule: String,
   epsilon: String,
   name: String,
   command: String,
   shell: Boolean,
   runAsUser: String,
   container: Container,
   cpus: String,
   mem: String,
   uris: List[Uri],
   async: Boolean,
   owner: String,
   environmentVariables: List[EnvironmentVariable]
)

object ChronosJob extends DefaultJsonProtocol {
  implicit val dockerParameterFormat: RootJsonFormat[DockerParameter] = jsonFormat2(DockerParameter.apply)
  implicit val containerFormat: RootJsonFormat[Container] = jsonFormat4(Container.apply)
  implicit val environmentVariableFormat: RootJsonFormat[EnvironmentVariable] = jsonFormat2(EnvironmentVariable.apply)
  implicit val uriFormat: RootJsonFormat[Uri] = jsonFormat1(Uri.apply)
  implicit val chronosJobFormat: RootJsonFormat[ChronosJob] = jsonFormat13(ChronosJob.apply)
}