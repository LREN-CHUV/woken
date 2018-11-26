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

import cats.effect.Effect
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.TableColumn
import ch.chuv.lren.woken.messages.query.ValidationSpec
import ch.chuv.lren.woken.messages.variables.SqlType

// TODO: support Training-test split for longitudinal datasets
// https://www.quora.com/Is-it-better-using-training-test-split-or-k-fold-CV-when-we-are-working-with-large-datasets

trait FeaturesSplitter {

  def splitFeatures(query: FeaturesQuery): List[FeaturesQuery]

}

object FeaturesSplitter {

  def defineSplitters(splitters: List[ValidationSpec]): Map[TableColumn, TableColumn => String] =
    splitters.map { spec =>
      spec.code match {
        case "kfold" => {
          val numFolds = spec.parametersAsMap("k").toInt
          TableColumn(s"_win_kfold_$numFolds", SqlType.int) -> { rndColumn: TableColumn =>
            s"ntile($numFolds) over (order by ${rndColumn.quotedName})"
          }
        }
        case _ => throw new IllegalArgumentException("Not handled")
      }

    }

  def prepareSplits[F[_]: Effect](splitters: List[ValidationSpec],
                                  featuresQuery: FeaturesQuery): F[List[FeaturesSplitter]] =
    splitters.map { spec =>
      spec.code match {
        case "kfold" => {
          new KFoldFeaturesSplitter(spec.parametersAsMap("k").toInt)
        }
        case _ => throw new IllegalArgumentException("Not handled")
      }

    }

}
