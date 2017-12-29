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

import eu.hbp.mip.woken.util.JsonUtils
import org.scalatest._

class ChronosJobLivelinessTest extends FlatSpec with Matchers with JsonUtils {

  "A response from Chronos" should "be parsable" in {

    import ChronosJobLiveliness._
    import spray.json._
    import DefaultJsonProtocol._

    val response   = loadJson("/backends/chronos/chronos-job-response.json")
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
