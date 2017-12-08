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
import eu.hbp.mip.woken.core.model.Variables
import spray.json.JsObject

import scala.collection.mutable
import scala.language.higherKinds

class MetadataRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends MetadataRepository[F] {

  override def variables: VariablesRepository[F] = new VariablesRepositoryDAO[F](xa)
}

class VariablesRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends VariablesRepository[F] {
  implicit val JsObjectMeta: Meta[JsObject] = DAL.JsObjectMeta

  // TODO: use a real cache, for example ScalaCache + Caffeine
  val variablesCache: mutable.Map[String, Variables] = new mutable.WeakHashMap[String, Variables]()

  override def put(v: Variables): F[Variables] =
    sql"""
        INSERT INTO meta_variables (source,
                             hierarchy,
                             target_table,
                             histogram_groupings)
              VALUES (${v.source},
                      ${v.hierarchy},
                      ${v.featuresTable},
                      ${v.defaultHistogramGroupings})
      """.update
      .withUniqueGeneratedKeys[Variables]("id",
                                          "source",
                                          "hierarchy",
                                          "target_table",
                                          "histogram_groupings")
      .transact(xa)

  override def get(featuresTable: String): F[Option[Variables]] = {
    val v = variablesCache.get(featuresTable)

    v.fold(
      sql"SELECT id, source, hierarchy, target_table, histogram_groupings FROM meta_variables WHERE target_table='${featuresTable.toUpperCase}'"
        .query[Variables]
        .option
        .transact(xa)
        .map { (r: Option[Variables]) =>
          r.foreach(variablesCache.put(featuresTable, _))
          r
        }
    )(Option(_).pure[F])

  }
}
