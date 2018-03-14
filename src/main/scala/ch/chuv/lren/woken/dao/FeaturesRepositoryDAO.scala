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

import cats.Monad
import ch.chuv.lren.woken.core.features.FeaturesQuery
import doobie._, doobie.implicits._
import spray.json.JsObject

import scala.language.higherKinds

class FeaturesRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends FeaturesRepository[F] {

  override def featuresTable(table: String): FeaturesTableRepository[F] =
    new FeaturesTableRepositoryDAO[F](xa, table)

}

class FeaturesTableRepositoryDAO[F[_]: Monad](val xa: Transactor[F], val table: String)
    extends FeaturesTableRepository[F] {

  override def count: F[Int] = {
    val q: Fragment = fr"select count(*) from $table"
    q.query[Int]
      .unique
      .transact(xa)
  }

  override def features(query: FeaturesQuery): (List[ColumnMeta], Stream[JsObject]) = ???
  // TODO: https://github.com/tpolecat/doobie/blob/series/0.5.x/modules/example/src/main/scala/example/Dynamic.scala

}
