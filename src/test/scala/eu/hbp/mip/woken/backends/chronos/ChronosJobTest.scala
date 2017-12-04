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

import eu.hbp.mip.woken.backends.chronos.{ EnvironmentVariable => EV, Parameter => P }
import org.scalatest._

class ChronosJobTest extends FlatSpec with Matchers {

  "A Chronos job" should "be serializable and compatible with Chronos REST API" in {

    val container = Container(`type` = ContainerType.DOCKER,
                              image = "hbpmip/somealgo",
                              network = NetworkMode.BRIDGE,
                              parameters = List(P("network", "bridge1")))

    val environmentVariables: List[EV] =
      List(EV("JOB_ID", "12345"), EV("NODE", "local"), EV("DOCKER_IMAGE", "hbpmip/somealgo"))

    val job = ChronosJob(
      name = "hbpmip_somealgo_1",
      command = "compute",
      shell = false,
      schedule = "R1//PT24H",
      epsilon = Some("PT5M"),
      runAsUser = Some("root"),
      container = Some(container),
      cpus = Some(0.5),
      mem = Some(512),
      owner = Some("mip@chuv.ch"),
      environmentVariables = environmentVariables,
      retries = 0
    )

    import ChronosJob._
    import spray.json._

    val expected =
      """
        |{
        |  "arguments": [],
        |  "command": "compute",
        |  "container": {
        |    "forcePullImage": false,
        |    "image": "hbpmip/somealgo",
        |    "network": "BRIDGE",
        |    "networkInfos":[],
        |    "parameters": [{
        |      "key": "network",
        |      "value": "bridge1"
        |    }],
        |    "type": "DOCKER",
        |    "volumes": []
        |  },
        |  "cpus": 0.5,
        |  "disabled": false,
        |  "environmentVariables": [{
        |    "name": "JOB_ID",
        |    "value": "12345"
        |  }, {
        |    "name": "NODE",
        |    "value": "local"
        |  }, {
        |    "name": "DOCKER_IMAGE",
        |    "value": "hbpmip/somealgo"
        |  }],
        |  "epsilon": "PT5M",
        |  "highPriority": false,
        |  "mem": 512.0,
        |  "name": "hbpmip_somealgo_1",
        |  "owner": "mip@chuv.ch",
        |  "runAsUser": "root",
        |  "retries": 0,
        |  "schedule": "R1//PT24H",
        |  "shell": false
        |}
      """.stripMargin.parseJson

    job.toJson shouldBe expected

  }

}
