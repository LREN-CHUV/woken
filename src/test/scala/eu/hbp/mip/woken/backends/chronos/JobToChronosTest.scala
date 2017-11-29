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

import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.messages.external.{ Algorithm, MiningQuery }
import org.scalatest.{ FlatSpec, Matchers }

class JobToChronosTest extends FlatSpec with Matchers {

  "A generic Docker job" should "be converted to a Chronos job definition" in {

    val algorithm = Algorithm(
      code = "knn",
      name = "KNN",
      parameters = Map("k" -> "5")
    )

    /*
    TODO

val query = MiningQuery(
  variables = List("target"),
  covariables = List("a", "b", "c"),
  grouping = List("grp1", "grp2"),
  filters = "a > 10",
  algorithm = algorithm
)

val dockerJob = DockerJob(
  jobId = "1234",
  dockerImage = "hbpmpi/test",
  inputDb = "features_db",
  inputTable = "features_table",
  query = query,

  parameters = Map(("k" -> 1), ("n" -> 10))
)
   */
  }

}
