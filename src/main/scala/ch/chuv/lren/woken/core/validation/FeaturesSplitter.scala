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
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.dao.utils._
import ch.chuv.lren.woken.messages.query.ValidationSpec
import ch.chuv.lren.woken.messages.variables.SqlType
import doobie._
import doobie.implicits._

// TODO: support Training-test split for longitudinal datasets
// https://www.quora.com/Is-it-better-using-training-test-split-or-k-fold-CV-when-we-are-working-with-large-datasets

trait FeaturesSplitter {

  def splitFeatures(query: FeaturesQuery): List[FeaturesQuery]

}

trait FeaturesSplitterDefinition {

  def splitColumn: TableColumn

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fillSplitColumnSql(rndColumn: TableColumn, targetTable: FeaturesTableDescription)(
      implicit h: LogHandler = LogHandler.nop
  ): Update0

}

object FeaturesSplitter {

  def defineSplitters(splitters: List[ValidationSpec]): List[FeaturesSplitterDefinition] =
    splitters.map { spec =>
      spec.code match {
        case "kfold" => {
          val numFolds = spec.parametersAsMap("k").toInt
          new FeaturesSplitterDefinition {
            override val splitColumn: TableColumn =
              TableColumn(s"_win_kfold_$numFolds", SqlType.int)
            @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
            override def fillSplitColumnSql(
                rndColumn: TableColumn,
                targetTable: FeaturesTableDescription
            )(implicit h: LogHandler = LogHandler.nop): Update0 = {
              val winTable = targetTable.copy(name = "win", datasetColumn = None)
              val stmt = fr"WITH win as (SELECT " ++ frNames(targetTable.primaryKey) ++ fr", ntile(" ++
                frConst(numFolds) ++ fr") over (order by rnd) as win FROM " ++ frName(targetTable) ++
                fr") UPDATE cde_features_a_1 SET " ++ frName(splitColumn) ++ fr"= win.win FROM win WHERE " ++
                frEqual(targetTable, targetTable.primaryKey, winTable, targetTable.primaryKey) ++ fr";"
              stmt.update
            }
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
