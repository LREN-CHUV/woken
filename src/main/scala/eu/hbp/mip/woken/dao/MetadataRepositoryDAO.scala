/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.dao

import doobie._
import doobie.implicits._
import cats._
import cats.implicits._
import eu.hbp.mip.woken.core.model.VariablesMeta
import spray.json.JsObject

import scala.collection.mutable
import scala.language.higherKinds

class MetadataRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends MetadataRepository[F] {

  override def variablesMeta: VariablesMetaRepository[F] = new VariablesMetaRepositoryDAO[F](xa)
}

class VariablesMetaRepositoryDAO[F[_]: Monad](val xa: Transactor[F])
    extends VariablesMetaRepository[F] {
  implicit val JsObjectMeta: Meta[JsObject] = DAL.JsObjectMeta

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
    val v = variablesMetaCache.get(targetFeaturesTable)

    v.fold(
      sql"SELECT id, source, hierarchy, target_table, histogram_groupings FROM meta_variables WHERE target_table=${targetFeaturesTable.toUpperCase}"
        .query[VariablesMeta]
        .option
        .transact(xa)
        .map { (r: Option[VariablesMeta]) =>
          r.foreach(variablesMetaCache.put(targetFeaturesTable, _))
          r
        }
    )(Option(_).pure[F])

  }
}
