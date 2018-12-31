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

import org.scalamock.scalatest.MockFactory
import org.scalatest.{ Matchers, WordSpec, fixture }
import acolyte.jdbc.{ AcolyteDSL, QueryExecution, UpdateExecution, Driver => AcolyteDriver }
import acolyte.jdbc.RowLists.{ rowList1, rowList3 }
import acolyte.jdbc.Implicits._
import cats.effect.{ Async, ContextShift, IO, Resource }
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import doobie.implicits._
import cats.implicits._
import cats.data._
import cats._
import cats.effect.internals.IOContextShift
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.messages.variables.GroupMetaData
import ch.chuv.lren.woken.messages.variables.variablesProtocol._

class MetadataRepositoryDAOTest extends WordSpec with Matchers with MockFactory with JsonUtils {

  "VariablesMetaRepository" should {
    // TODO: Acolyte should support pgObject and pgJsonb types
    "put and get variables" ignore withVariablesMetaRepository { dao =>
      val churnHierarchy = loadJson("/metadata/churn_variables.json").convertTo[GroupMetaData]
      val churnVariablesMeta =
        VariablesMeta(1, "churn", churnHierarchy, "CHURN", List("state", "custserv_calls", "churn"))

      val updated = dao.put(churnVariablesMeta).unsafeRunSync()

      updated shouldBe churnVariablesMeta

      val retrieved = dao.get("CHURN").unsafeRunSync()

      retrieved shouldBe churnVariablesMeta
    }
  }

  def withVariablesMetaRepository(testCode: VariablesMetaRepositoryDAO[IO] => Any): Unit = {

    val handlerA = AcolyteDSL.handleQuery { q =>
      println(q.sql)

      1

    }

    val conn: Connection              = AcolyteDSL.connection(handlerA)
    implicit val cs: ContextShift[IO] = IOContextShift.global

    // Resource yielding a Transactor[IO] wrapping the given `Connection`
    def transactor(c: Connection): Resource[IO, Transactor[IO]] =
      ExecutionContexts.cachedThreadPool[IO].flatMap { te =>
        val t: Transactor[IO] = Transactor.fromConnection[IO](c, te)
        Resource.liftF(t.configure(_ => IO.pure(t)))
      }

    transactor(conn)
      .use { tr =>
        val dao = new VariablesMetaRepositoryDAO[IO](tr)
        IO.delay(testCode(dao))
      }
      .unsafeRunSync()
  }

}
