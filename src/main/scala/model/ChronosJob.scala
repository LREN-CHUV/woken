package models

import spray.json
import spray.json._

case class Container(
  `type`: String,
  image: String,
  network: String
)

case class EnvironmentVariables(
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
   environmentVariables: List[EnvironmentVariables]
)

object ChronosJob extends DefaultJsonProtocol {
  implicit val containerFormat = jsonFormat3(Container.apply)
  implicit val environmentVariablesFormat = jsonFormat2(EnvironmentVariables.apply)
  implicit val uriFormat = jsonFormat1(Uri.apply)
  implicit val chronosJobFormat = jsonFormat13(ChronosJob.apply)
}