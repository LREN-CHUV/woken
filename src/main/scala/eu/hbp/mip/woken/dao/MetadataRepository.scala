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
import eu.hbp.mip.woken.core.model.Variables
import spray.json.{ JsArray, JsObject, deserializationError }

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
  * The interface to Metadata database
  */
trait MetadataRepository[F[_]] extends DAL {

  def variables: VariablesRepository[F]

}

trait VariablesRepository[F[_]] {

  def put(variables: Variables): F[Variables]

  def get(featuresTable: String): F[Option[Variables]]

}

class MetadataInMemoryRepository[F[_]: Applicative] extends MetadataRepository[F] {

  override val variables: VariablesRepository[F] = new VariablesRepository[F] {

    private val cache = new TrieMap[String, Variables]

    override def put(variables: Variables): F[Variables] = {
      cache.put(variables.featuresTable, variables)
      variables.pure[F]
    }

    override def get(featuresTable: String): F[Option[Variables]] =
      cache.get(featuresTable).pure[F]

  }

}
