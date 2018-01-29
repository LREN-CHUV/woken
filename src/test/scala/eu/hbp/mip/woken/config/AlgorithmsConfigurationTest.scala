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

package eu.hbp.mip.woken.config

import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
import AlgorithmsConfiguration._

class AlgorithmsConfigurationTest extends WordSpec with Matchers with ConfigurationLoader {

  "Configuration for algorithms" should {

    "read default list of algorithms" in {

      val algoConfigs = factory(ConfigFactory.load())

      val histograms = algoConfigs("histograms").valueOr(configurationFailed)
      histograms.code shouldBe "histograms"
      histograms.predictive shouldBe false
      histograms.supportsNullValues shouldBe true
      histograms.dockerImage.contains("hbpmip") shouldBe true

      val anova = algoConfigs("anova").valueOr(configurationFailed)
      anova.code shouldBe "anova"
      anova.predictive shouldBe false
      anova.supportsNullValues shouldBe false
      anova.dockerImage.contains("hbpmip") shouldBe true

      val knn = algoConfigs("knn").valueOr(configurationFailed)
      knn.code shouldBe "knn"
      knn.predictive shouldBe true
      knn.supportsNullValues shouldBe false
      knn.dockerImage.contains("hbpmip") shouldBe true
    }
  }

}
