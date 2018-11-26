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

import cats.effect.concurrent.Deferred
import ch.chuv.lren.woken.core.features.FeaturesQuery

class KFoldFeaturesSplitter[F[_]](val numFolds: Int, val dynTable: Deferred[F, String])
    extends FeaturesSplitter {

  override def splitFeatures(query: FeaturesQuery): List[FeaturesQuery] =
    ???

  private def calculateSplitIndices(numExamples: Int): List[Int] = {
    val atLeastNumExamplesPerFold = List.fill(numFolds)(numExamples / numFolds)
    val numFoldsWithOneMore       = numExamples % numFolds

    val numExamplesPerFold = atLeastNumExamplesPerFold.zipWithIndex map {
      case (num, i) if i < numFoldsWithOneMore => num + 1
      case (num, _)                            => num
    }

    // calculate indices by subtracting number of examples per fold from total number of examples
    numExamplesPerFold.foldRight(List(numExamples)) {
      case (num, head :: tail) => head - num :: head :: tail
      case _                   => throw new IllegalStateException()
    }
  }
}
object KFoldFeaturesSplitter {

  def main(args: Array[String]): Unit = {
    val k = new KFoldFeaturesSplitter(10)
    print(k.calculateSplitIndices(32))
  }
}
