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

import cats._
import cats.implicits._
import eu.hbp.mip.woken.core.model.VariablesMeta
import spray.json.{ JsArray, JsObject, deserializationError }

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
  * The interface to Metadata database
  */
trait MetadataRepository[F[_]] extends DAL {

  def variablesMeta: VariablesMetaRepository[F]

}

trait VariablesMetaRepository[F[_]] {

  def put(variablesMeta: VariablesMeta): F[VariablesMeta]

  def get(targetFeaturesTable: String): F[Option[VariablesMeta]]

}

class MetadataInMemoryRepository[F[_]: Applicative] extends MetadataRepository[F] {

  override val variablesMeta: VariablesMetaRepository[F] = new VariablesMetaRepository[F] {

    private val cache = new TrieMap[String, VariablesMeta]

    override def put(variablesMeta: VariablesMeta): F[VariablesMeta] = {
      cache.put(variablesMeta.targetFeaturesTable, variablesMeta)
      variablesMeta.pure[F]
    }

    override def get(targetFeaturesTable: String): F[Option[VariablesMeta]] =
      cache.get(targetFeaturesTable).pure[F]

  }

}
