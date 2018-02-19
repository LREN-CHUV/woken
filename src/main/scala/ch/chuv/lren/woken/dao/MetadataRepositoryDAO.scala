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
import cats.implicits._
import ch.chuv.lren.woken.messages.variables.{ GroupMetaData, variablesProtocol }
import ch.chuv.lren.woken.core.model.VariablesMeta
import spray.json.JsObject
import variablesProtocol._

import scala.collection.mutable
import scala.language.higherKinds

class MetadataRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends MetadataRepository[F] {

  override def variablesMeta: VariablesMetaRepository[F] = new VariablesMetaRepositoryDAO[F](xa)
}

class VariablesMetaRepositoryDAO[F[_]: Monad](val xa: Transactor[F])
    extends VariablesMetaRepository[F] {

  implicit val groupMetaDataMeta: Meta[GroupMetaData] = codecMeta[GroupMetaData]

  // TODO: use a real cache, for example ScalaCache + Caffeine
  val variablesMetaCache: mutable.Map[String, VariablesMeta] =
    new mutable.WeakHashMap[String, VariablesMeta]()

  override def put(v: VariablesMeta): F[VariablesMeta] =
    sql"""
        INSERT INTO meta_variables (source,
                             hierarchy,
                             target_table,
                             histogram_groupings)
              VALUES (${v.source},
                      ${v.hierarchy},
                      ${v.targetFeaturesTable},
                      ${v.defaultHistogramGroupings})
      """.update
      .withUniqueGeneratedKeys[VariablesMeta]("id",
                                              "source",
                                              "hierarchy",
                                              "target_table",
                                              "histogram_groupings")
      .transact(xa)

  override def get(targetFeaturesTable: String): F[Option[VariablesMeta]] = {
    val table = targetFeaturesTable.toUpperCase
    val v     = variablesMetaCache.get(table)

    v.fold(
      sql"SELECT id, source, hierarchy, target_table, histogram_groupings FROM meta_variables WHERE UPPER(target_table)=UPPER($table)"
        .query[VariablesMeta]
        .option
        .transact(xa)
        .map { (r: Option[VariablesMeta]) =>
          r.foreach(variablesMetaCache.put(table, _))
          r
        }
    )(Option(_).pure[F])

  }
}
