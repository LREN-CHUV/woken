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
import ch.chuv.lren.woken.core.model.database.{ FeaturesTableDescription, TableColumn }
import ch.chuv.lren.woken.core.model.database.sqlUtils.{ frConst, frEqual, frName, frNames }
import ch.chuv.lren.woken.messages.query.ValidationSpec
import ch.chuv.lren.woken.messages.query.filters._
import ch.chuv.lren.woken.messages.variables.SqlType
import ch.chuv.lren.woken.service.FeaturesTableService
import doobie._
import doobie.implicits._

import scala.language.higherKinds

case class KFoldFeaturesSplitterDefinition(override val validation: ValidationSpec,
                                           override val numFolds: Int)
    extends FeaturesSplitterDefinition {

  override val splitColumn: TableColumn =
    TableColumn(s"_win_kfold_$numFolds", SqlType.int)

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  override def fillSplitColumnSql(
      sourceTable: FeaturesTableDescription,
      targetTable: FeaturesTableDescription,
      rndColumn: TableColumn
  )(implicit h: LogHandler = LogHandler.nop): Update0 = {
    val winTable =
      targetTable.copy(table = targetTable.table.copy(name = "win"), validateSchema = false)

    val stmt = fr"""WITH "win" as (SELECT """ ++ frNames(targetTable.primaryKey) ++ fr", ntile(" ++
      frConst(numFolds) ++ fr") over (order by " ++ frName(rndColumn) ++ fr") as win FROM " ++
      frName(targetTable) ++ fr") UPDATE " ++ frName(targetTable) ++ fr"SET " ++ frName(splitColumn) ++
      fr"""= "win".win FROM win WHERE """ ++
      frEqual(targetTable, targetTable.primaryKey, winTable, winTable.primaryKey) ++ fr";"
    stmt.update
  }

  override def makeSplitter[F[_]: Effect](
      targetTable: FeaturesTableService[F]
  ): FeaturesSplitter[F] =
    KFoldFeaturesSplitter(definition = this, targetTable = targetTable)
}

case class KFoldFeaturesSplitter[F[_]](
    override val definition: KFoldFeaturesSplitterDefinition,
    override val targetTable: FeaturesTableService[F]
) extends FeaturesSplitter[F] {

  override def splitFeatures(query: FeaturesQuery): List[PartioningQueries] =
    // ntile also starts from 1
    Range(1, definition.numFolds + 1).toList
      .map { fold =>
        PartioningQueries(fold = fold,
                          trainingDatasetQuery = trainingDatasetQuery(query, fold),
                          testDatasetQuery = testDatasetQuery(query, fold))
      }

  private def trainingDatasetQuery(query: FeaturesQuery, fold: Int): FeaturesQuery = query.copy(
    dbTable = targetTable.table.table,
    filters = andSplitOnFold(query.filters, fold, Operator.notEqual)
  )

  private def testDatasetQuery(query: FeaturesQuery, fold: Int): FeaturesQuery = query.copy(
    dbTable = targetTable.table.table,
    filters = andSplitOnFold(query.filters, fold, Operator.equal)
  )

  private def andSplitOnFold(previousFilters: Option[FilterRule],
                             fold: Int,
                             operator: Operator.Operator): Option[FilterRule] = {

    val splitRule = SingleFilterRule("split",
                                     definition.splitColumn.name,
                                     "int",
                                     InputType.number,
                                     operator,
                                     List(fold.toString))

    Some(
      previousFilters.fold(splitRule: FilterRule)(
        f => CompoundFilterRule(Condition.and, List(f, splitRule))
      )
    )
  }
}
