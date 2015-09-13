package models

import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

case class Container(
  tpe: String,
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

trait ChronosJobJSONTrait {
  implicit val ContainerFormat = (
    (__ \ "type").format[String] and
    (__ \ "image").format[String] and
    (__ \ "network").format[String]
  ) (Container.apply, unlift(Container.unapply))
  implicit val EnvironmentVariablesFormat = Json.format[EnvironmentVariables]
  implicit val UriFormat = Json.format[Uri]
  implicit val ChronosJobFormat = Json.format[ChronosJob]
}