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
      environmentVariables = environmentVariables
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
        |  "schedule": "R1//PT24H",
        |  "shell": false
        |}
      """.stripMargin.parseJson

    job.toJson shouldBe expected

  }

  "A response from Chronos" should "be parsable" in {

    val response =
      """
        |[{"name":"java_rapidminer_knn_78be29d9_bc05_4db2_a2e0_c3de218960bc","command":"compute","shell":false,"executor":"",
        |"executorFlags":"","taskInfoData":"","retries":2,"owner":"admin@mip.chuv.ch","ownerName":"","description":"","successCount":1,
        |"errorCount":0,"lastSuccess":"2017-11-29T23:13:01.724Z","lastError":"","cpus":0.5,"disk":256.0,"mem":512.0,"disabled":true,
        |"softError":false,"dataProcessingJobType":false,"errorsSinceLastSuccess":0,"fetch":[],"uris":[],
        |"environmentVariables":[{"name":"JOB_ID","value":"78be29d9-bc05-4db2-a2e0-c3de218960bc"},{"name":"NODE","value":"federation"},
        |{"name":"DOCKER_IMAGE","value":"hbpmip/java-rapidminer-knn:0.2.1"},{"name":"PARAM_variables","value":"cognitive_task2"},
        |{"name":"PARAM_query","value":"select cognitive_task2,score_test1,college_math from sample_data where cognitive_task2 is not null and score_test1 is not null and college_math is not null  EXCEPT ALL (select cognitive_task2,score_test1,college_math from sample_data where cognitive_task2 is not null and score_test1 is not null and college_math is not null  OFFSET 0 LIMIT 75)"},
        |{"name":"PARAM_grouping","value":""},{"name":"PARAM_meta",
        |"value":"{\"cognitive_task2\":{\"description\":\"\",\"methodology\":\"test\",\"label\":\"Cognitive Task 2\",\"code\":\"cognitive_task2\",\"type\":\"real\"},\"score_test1\":{\"description\":\"\",\"methodology\":\"test\",\"label\":\"Score Test 1\",\"code\":\"score_test1\",\"type\":\"real\"},\"college_math\":{\"description\":\"\",\"methodology\":\"test\",\"label\":\"College Math\",\"code\":\"college_math\",\"type\":\"real\"}}"},
        |{"name":"PARAM_covariables","value":"score_test1,college_math"},{"name":"IN_JDBC_DRIVER","value":"org.postgresql.Driver"},
        |{"name":"IN_JDBC_URL","value":"jdbc:postgresql://db:5432/features"},{"name":"IN_JDBC_USER","value":"postgres"},{"name":"IN_JDBC_PASSWORD","value":"test"},
        |{"name":"OUT_JDBC_DRIVER","value":"org.postgresql.Driver"},{"name":"OUT_JDBC_URL","value":"jdbc:postgresql://db:5432/woken"},
        |{"name":"OUT_JDBC_USER","value":"postgres"},{"name":"OUT_JDBC_PASSWORD","value":"test"}],"arguments":[],"highPriority":false,
        |"runAsUser":"root","concurrent":false,"container":{"type":"DOCKER","image":"hbpmip/java-rapidminer-knn:0.2.1","network":"BRIDGE",
        |"networkInfos":[],"volumes":[],"forcePullImage":false,"parameters":[{"key":"network","value":"devtests_default"}]},"constraints":[],
        |"schedule":"R0/2017-11-29T23:13:40.989Z/PT1M","scheduleTimeZone":""}]
      """.stripMargin

  }
}
