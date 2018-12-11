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
import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.dao.PrefillExtendedFeaturesTable
import ch.chuv.lren.woken.messages.query.ValidationSpec
import ch.chuv.lren.woken.service.FeaturesTableService
import doobie.{ LogHandler, Update0 }

import scala.language.higherKinds

// TODO: support Training-test split for longitudinal datasets
// https://www.quora.com/Is-it-better-using-training-test-split-or-k-fold-CV-when-we-are-working-with-large-datasets

case class PartioningQueries(fold: Int,
                             trainingDatasetQuery: FeaturesQuery,
                             testDatasetQuery: FeaturesQuery)

trait FeaturesSplitter[F[_]] {

  def targetTable: FeaturesTableService[F]

  def definition: FeaturesSplitterDefinition

  def splitFeatures(query: FeaturesQuery): List[PartioningQueries]

}

trait FeaturesSplitterDefinition extends PrefillExtendedFeaturesTable {

  def numFolds: Int

  def validation: ValidationSpec

  def splitColumn: TableColumn

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fillSplitColumnSql(sourceTable: FeaturesTableDescription,
                         targetTable: FeaturesTableDescription,
                         rndColumn: TableColumn)(
      implicit h: LogHandler = LogHandler.nop
  ): Update0

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  override final def prefillExtendedTableSql(sourceTable: FeaturesTableDescription,
                                             targetTable: FeaturesTableDescription,
                                             rndColumn: TableColumn)(
      implicit h: LogHandler = LogHandler.nop
  ): Update0 = fillSplitColumnSql(sourceTable, targetTable, rndColumn)

  def makeSplitter[F[_]: Effect](targetTable: FeaturesTableService[F]): FeaturesSplitter[F]

}

object FeaturesSplitter {

  def apply[F[_]: Effect](
      splitterDef: FeaturesSplitterDefinition,
      targetTable: FeaturesTableService[F]
  ): FeaturesSplitter[F] =
    splitterDef.makeSplitter(targetTable)

}
