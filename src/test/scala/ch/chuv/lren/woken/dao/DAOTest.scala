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
import java.sql.Connection

import acolyte.jdbc.{ AbstractCompositeHandler, AcolyteDSL }
import cats.effect.{ ContextShift, IO, Resource }
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor

trait DAOTest[DAO <: Repository[IO]] {

  def withRepository(sqlHandler: AbstractCompositeHandler[_],
                     mkDAO: Transactor[IO] => DAO)(testCode: DAO => Any): Unit = {

    val conn: Connection = AcolyteDSL.connection(sqlHandler)
    implicit val cs: ContextShift[IO] =
      IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

    // Resource yielding a Transactor[IO] wrapping the given `Connection`
    def transactor(c: Connection): Resource[IO, Transactor[IO]] =
      ExecutionContexts.cachedThreadPool[IO].flatMap { te =>
        val t: Transactor[IO] = Transactor.fromConnection[IO](c, te)
        Resource.liftF(t.configure(_ => IO.pure(t)))
      }

    transactor(conn)
      .use { tr =>
        val dao = mkDAO(tr)
        IO.delay(testCode(dao))
      }
      .unsafeRunSync()
  }

}
