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

package ch.chuv.lren.woken.dao

import doobie._
import doobie.implicits._
import cats._
import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.core.logging
import ch.chuv.lren.woken.messages.variables.{ GroupMetaData, variablesProtocol }
import sup.HealthCheck
import ch.chuv.lren.woken.core.model.database.FeaturesTableDescription
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.messages.datasets.TableId
import variablesProtocol._

import scala.collection.mutable
import scala.language.higherKinds

class VariablesMetaRepositoryDAO[F[_]](val xa: Transactor[F])(implicit F: MonadError[F, Throwable])
    extends VariablesMetaRepository[F] {

  implicit val groupMetaDataMeta: Meta[GroupMetaData] = codecMeta[GroupMetaData]

  implicit val han: LogHandler = logging.doobieLogHandler

  // TODO: use a real cache, for example ScalaCache + Caffeine
  val variablesMetaCache: mutable.Map[TableId, VariablesMeta] =
    new mutable.WeakHashMap[TableId, VariablesMeta]()

  override def put(v: VariablesMeta): F[VariablesMeta] = {
    // TODO: add database and schema to the values
    val database = v.targetFeaturesTable.database
    val schema   = v.targetFeaturesTable.dbSchema
    val table    = v.targetFeaturesTable.name
    sql"""
        INSERT INTO meta_variables (source,
                             hierarchy,
                             target_table,
                             histogram_groupings)
              VALUES (${v.source},
                      ${v.hierarchy},
                      $table,
                      ${v.defaultHistogramGroupings})
      """.update
      .withUniqueGeneratedKeys[VariablesMeta]("id",
                                              "source",
                                              "hierarchy",
                                              "target_table",
                                              "histogram_groupings")
      .transact(xa)
  }

  override def get(targetFeaturesTable: TableId): F[Option[VariablesMeta]] = {
    val database = targetFeaturesTable.database
    val schema   = targetFeaturesTable.dbSchema
    val table    = targetFeaturesTable.name
    val v        = variablesMetaCache.get(targetFeaturesTable)

    // TODO: add database and schema to the where clause
    // TODO: collapse database,schema,table into a TableId in the Doobie mappings
    v.fold(
      sql"SELECT id, source, hierarchy, 'features' as database, 'public' as db_schema, target_table as name, histogram_groupings FROM meta_variables WHERE upper(target_table)=upper($table)"
        .query[VariablesMeta]
        .option
        .transact(xa)
        .map { r: Option[VariablesMeta] =>
          r.foreach(variablesMetaCache.put(targetFeaturesTable, _))
          r
        }
    )(Option(_).pure[F])

  }

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}

class TablesCatalogRepositoryDAO[F[_]](val xa: Transactor[F])(implicit F: MonadError[F, Throwable])
    extends TablesCatalogRepository[F] {

  override def put(table: FeaturesTableDescription): F[FeaturesTableDescription] = ???

  override def get(table: TableId): F[Option[FeaturesTableDescription]] = ???

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}

case class MetadataRepositoryDAO[F[_]](xa: Transactor[F])(implicit F: MonadError[F, Throwable]) extends MetadataRepository[F] {

  override def variablesMeta: VariablesMetaRepository[F] = new VariablesMetaRepositoryDAO[F](xa)

  override def tablesCatalog: TablesCatalogRepository[F] = new TablesCatalogRepositoryDAO[F](xa)

  override def healthCheck: HealthCheck[F, Id] = validate(xa)
}
