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

package ch.chuv.lren.woken.validation

import cats.effect.Effect
import cats.effect.concurrent.Deferred
import cats.implicits._
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.ValidationSpec
import doobie.{ LogHandler, Update0 }

import scala.language.higherKinds

// TODO: support Training-test split for longitudinal datasets
// https://www.quora.com/Is-it-better-using-training-test-split-or-k-fold-CV-when-we-are-working-with-large-datasets

trait FeaturesSplitter[F[_]] {

  def splitFeatures(query: FeaturesQuery): F[List[FeaturesQuery]]

}

trait FeaturesSplitterDefinition {

  def splitColumn: TableColumn

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fillSplitColumnSql(targetTable: FeaturesTableDescription, rndColumn: TableColumn)(
      implicit h: LogHandler = LogHandler.nop
  ): Update0

  def makeSplitter[F[_]: Effect, A](
      dynTable: Deferred[F, FeaturesTableDescription],
      dynView: Deferred[F, FeaturesTableDescription]
  ): FeaturesSplitter[F]
}

object FeaturesSplitter {

  def defineSplitters(
      splitters: List[ValidationSpec]
  ): Validation[List[FeaturesSplitterDefinition]] =
    splitters
      .map { spec =>
        spec.code match {
          case "kfold" =>
            val numFolds = spec.parametersAsMap("k").toInt
            KFoldFeaturesSplitter.kFoldSplitterDefinition(numFolds).validNel[String]

          case other => s"Validation $other is not handled".invalidNel[FeaturesSplitterDefinition]
        }
      }
      .sequence[Validation, FeaturesSplitterDefinition]

  def apply[F[_]: Effect](
      splitterDef: FeaturesSplitterDefinition,
      dynTable: Deferred[F, FeaturesTableDescription],
      dynView: Deferred[F, FeaturesTableDescription]
  ): FeaturesSplitter[F] =
    splitterDef.makeSplitter(dynTable, dynView)

}
