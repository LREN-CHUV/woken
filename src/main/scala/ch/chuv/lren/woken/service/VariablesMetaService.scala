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

package ch.chuv.lren.woken.service

import cats.effect.Effect
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.dao.VariablesMetaRepository

import scala.language.higherKinds

/**
  * Service that provides access to the metadata of the variables.
  *
  * @param repository where we get our data
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
class VariablesMetaService[F[_]: Effect](repository: VariablesMetaRepository[F]) {

  def put(meta: VariablesMeta): F[VariablesMeta] = repository.put(meta)

  def get(targetFeaturesTable: String): F[Option[VariablesMeta]] =
    repository.get(targetFeaturesTable)

}

object VariablesMetaService {
  def apply[F[_]: Effect](repo: VariablesMetaRepository[F]): VariablesMetaService[F] =
    new VariablesMetaService(repo)
}
