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

package ch.chuv.lren.woken.core.validation

import ch.chuv.lren.woken.util.JsonUtils
import org.scalatest._
import spray.json._

class KFoldCrossValidationTest extends WordSpec with Matchers with JsonUtils {

  "KFoldCrossValidation" should {

    "breakdown a dataframe of 2 columns into folds" in {

      val dataframe = loadJson("/core/validation/brain-volumes-2-cols.json")
        .asInstanceOf[JsArray]
        .elements
        .map(_.asJsObject)

      val crossValidation = KFoldCrossValidation.apply(dataframe.toStream,
                                                       List("leftcalccalcarinecortex"),
                                                       List("rightcerebralwhitematter"),
                                                       3)

      crossValidation.partition shouldBe Map(0 -> (0 -> 5), 1 -> (5 -> 6), 2 -> (11 -> 5))

      crossValidation.groundTruth(0) shouldBe List(3.6542, 3.3389, 3.6169, 3.7731, 2.5467).map(
        JsNumber.apply
      )

      crossValidation.groundTruth(1) shouldBe List(2.9703, 2.7326, 3.3412, 2.636, 2.9745,
        3.0936).map(JsNumber.apply)

      crossValidation.groundTruth(2) shouldBe List(2.8439, 2.8373, 2.5654, 2.8522, 3.877).map(
        JsNumber.apply
      )

      crossValidation.getTestSet(0) shouldBe ((List(
                                                 "rightcerebralwhitematter" -> 251.9128,
                                                 "rightcerebralwhitematter" -> 206.845,
                                                 "rightcerebralwhitematter" -> 270.7593,
                                                 "rightcerebralwhitematter" -> 195.6455,
                                                 "rightcerebralwhitematter" -> 197.4064
                                               ).map(jsObject),
                                               List(
                                                 "leftcalccalcarinecortex" -> 3.6542,
                                                 "leftcalccalcarinecortex" -> 3.3389,
                                                 "leftcalccalcarinecortex" -> 3.6169,
                                                 "leftcalccalcarinecortex" -> 3.7731,
                                                 "leftcalccalcarinecortex" -> 2.5467
                                               ).map(jsObject)))

      crossValidation.getTestSet(1) shouldBe ((List(
                                                 "rightcerebralwhitematter" -> 233.7051,
                                                 "rightcerebralwhitematter" -> 215.1196,
                                                 "rightcerebralwhitematter" -> 202.8273,
                                                 "rightcerebralwhitematter" -> 232.1245,
                                                 "rightcerebralwhitematter" -> 232.3607,
                                                 "rightcerebralwhitematter" -> 208.768
                                               ).map(jsObject),
                                               List(
                                                 "leftcalccalcarinecortex" -> 2.9703,
                                                 "leftcalccalcarinecortex" -> 2.7326,
                                                 "leftcalccalcarinecortex" -> 3.3412,
                                                 "leftcalccalcarinecortex" -> 2.636,
                                                 "leftcalccalcarinecortex" -> 2.9745,
                                                 "leftcalccalcarinecortex" -> 3.0936
                                               ).map(jsObject)))

      crossValidation.getTestSet(2) shouldBe ((List(
                                                 "rightcerebralwhitematter" -> 216.8783,
                                                 "rightcerebralwhitematter" -> 213.239,
                                                 "rightcerebralwhitematter" -> 212.1288,
                                                 "rightcerebralwhitematter" -> 249.6425,
                                                 "rightcerebralwhitematter" -> 211.353
                                               ).map(jsObject),
                                               List(
                                                 "leftcalccalcarinecortex" -> 2.8439,
                                                 "leftcalccalcarinecortex" -> 2.8373,
                                                 "leftcalccalcarinecortex" -> 2.5654,
                                                 "leftcalccalcarinecortex" -> 2.8522,
                                                 "leftcalccalcarinecortex" -> 3.877
                                               ).map(jsObject)))

    }
  }

  private def jsObject(kv: (String, Double)) = JsObject(kv._1 -> JsNumber(kv._2))
}
