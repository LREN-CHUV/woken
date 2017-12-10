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

package eu.hbp.mip.woken.service

import cats.effect.{ Effect, IO }
import eu.hbp.mip.woken.core.model.VariablesMeta
import eu.hbp.mip.woken.dao.VariablesMetaRepository

import scala.language.higherKinds

// Ok, end of the world IO occurs early on

/**
  * The entry point to our domain, works with repositories and validations to implement behavior
  * @param repository where we get our data
  */
class VariablesMetaService(repository: VariablesMetaRepository[IO])(implicit E: Effect[IO]) {

  def put(meta: VariablesMeta): VariablesMeta = repository.put(meta).unsafeRunSync()

  def get(targetFeaturesTable: String): Option[VariablesMeta] =
    repository.get(targetFeaturesTable).unsafeRunSync()

}

object VariablesMetaService {
  def apply(repo: VariablesMetaRepository[IO])(implicit E: Effect[IO]): VariablesMetaService =
    new VariablesMetaService(repo)
}
