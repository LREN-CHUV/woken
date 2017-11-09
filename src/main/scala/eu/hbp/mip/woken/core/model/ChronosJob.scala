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

package eu.hbp.mip.woken.core.model

import spray.json.{ DefaultJsonProtocol, RootJsonFormat }

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
  implicit val dockerParameterFormat: RootJsonFormat[DockerParameter] = jsonFormat2(
    DockerParameter.apply
  )
  implicit val containerFormat: RootJsonFormat[Container] = jsonFormat4(Container.apply)
  implicit val environmentVariableFormat: RootJsonFormat[EnvironmentVariable] = jsonFormat2(
    EnvironmentVariable.apply
  )
  implicit val uriFormat: RootJsonFormat[Uri]               = jsonFormat1(Uri.apply)
  implicit val chronosJobFormat: RootJsonFormat[ChronosJob] = jsonFormat13(ChronosJob.apply)
}
