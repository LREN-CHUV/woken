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

import java.time.OffsetDateTime

import org.scalatest._

class ChronosJobLivelinessTest extends FlatSpec with Matchers {

  "A response from Chronos" should "be parsable" in {

    import ChronosJobLiveliness._
    import spray.json._
    import DefaultJsonProtocol._

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
      """.stripMargin.parseJson

    val liveliness = response.convertTo[List[ChronosJobLiveliness]].head

    val expected = ChronosJobLiveliness(
      name = "java_rapidminer_knn_78be29d9_bc05_4db2_a2e0_c3de218960bc",
      successCount = 1,
      errorCount = 0,
      lastSuccess = Some(OffsetDateTime.parse("2017-11-29T23:13:01.724Z")),
      lastError = None,
      softError = false,
      errorsSinceLastSuccess = 0,
      disabled = true
    )

    liveliness shouldBe expected

  }
}
