/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.config

import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import AlgorithmsConfiguration._

class AlgorithmsConfigurationTest
    extends WordSpec
    with Matchers
    with ValidatedMatchers
    with ValidatedValues {

  "Configuration for algorithms" should {

    "read default list of algorithms" in {

      val algoConfigs = factory(ConfigFactory.load("algorithms.conf"))

      algoConfigs("histograms") shouldBe valid

      val histograms = algoConfigs("histograms").value
      histograms.code shouldBe "histograms"
      histograms.predictive shouldBe false
      histograms.supportsNullValues shouldBe true
      histograms.dockerImage.contains("hbpmip") shouldBe true

      algoConfigs("anova") shouldBe valid

      val anova = algoConfigs("anova").value
      anova.code shouldBe "anova"
      anova.predictive shouldBe false
      anova.supportsNullValues shouldBe false
      anova.dockerImage.contains("hbpmip") shouldBe true

      algoConfigs("knn") shouldBe valid

      val knn = algoConfigs("knn").value
      knn.code shouldBe "knn"
      knn.predictive shouldBe true
      knn.supportsNullValues shouldBe false
      knn.dockerImage.contains("hbpmip") shouldBe true
    }
  }

}
