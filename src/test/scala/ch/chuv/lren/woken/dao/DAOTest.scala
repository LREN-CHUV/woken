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

import java.sql.{
  Blob,
  CallableStatement,
  Clob,
  Connection,
  DatabaseMetaData,
  NClob,
  PreparedStatement,
  SQLWarning,
  SQLXML,
  Savepoint,
  Statement,
  Struct
}
import java.util.Properties
import java.{ sql, util }
import java.util.concurrent.Executor

import acolyte.jdbc.{ AbstractCompositeHandler, AcolyteDSL }
import cats.effect.{ ContextShift, IO, Resource }
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor

trait DAOTest {

  def withRepository[DAO <: Repository[IO]](
      sqlHandler: AbstractCompositeHandler[_],
      mkDAO: Transactor[IO] => DAO
  )(testCode: DAO => Any): Unit = {

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

  def withRepositoryResource[DAO <: Repository[IO]](
      sqlHandler: AbstractCompositeHandler[_],
      mkDAOResource: Transactor[IO] => Resource[IO, DAO]
  )(testCode: DAO => Any): Unit = {

    val conn: Connection = AcolyteDSL.connection(sqlHandler)
    val connHacked = new Connection {
      override def createStatement(): Statement                   = conn.createStatement()
      override def prepareStatement(s: String): PreparedStatement = conn.prepareStatement(s)
      override def prepareCall(s: String): CallableStatement      = conn.prepareCall(s)
      override def nativeSQL(s: String): String                   = conn.nativeSQL(s)
      override def setAutoCommit(b: Boolean): Unit                = conn.setAutoCommit(b)
      override def getAutoCommit: Boolean                         = conn.getAutoCommit
      override def commit(): Unit                                 = conn.commit()
      override def rollback(): Unit                               = conn.rollback()
      override def close(): Unit                                  = println("Connection close() called")
      override def isClosed: Boolean                              = conn.isClosed
      override def getMetaData: DatabaseMetaData                  = conn.getMetaData
      override def setReadOnly(b: Boolean): Unit                  = conn.setReadOnly(b)
      override def isReadOnly: Boolean                            = conn.isReadOnly
      override def setCatalog(s: String): Unit                    = conn.setCatalog(s)
      override def getCatalog: String                             = conn.getCatalog
      override def setTransactionIsolation(i: Int): Unit          = conn.setTransactionIsolation(i)
      override def getTransactionIsolation: Int                   = conn.getTransactionIsolation
      override def getWarnings: SQLWarning                        = conn.getWarnings
      override def clearWarnings(): Unit                          = conn.clearWarnings()
      override def createStatement(i: Int, i1: Int): Statement    = conn.createStatement(i, i1)
      override def prepareStatement(s: String, i: Int, i1: Int): PreparedStatement =
        conn.prepareStatement(s, i, i1)
      override def prepareCall(s: String, i: Int, i1: Int): CallableStatement =
        conn.prepareCall(s, i, i1)
      override def getTypeMap: util.Map[String, Class[_]] = conn.getTypeMap
      override def setTypeMap(
          map: util.Map[String, Class[_]]
      ): Unit                                                   = conn.setTypeMap(map)
      override def setHoldability(i: Int): Unit                 = conn.setHoldability(i)
      override def getHoldability: Int                          = conn.getHoldability
      override def setSavepoint(): Savepoint                    = conn.setSavepoint()
      override def setSavepoint(s: String): Savepoint           = conn.setSavepoint(s)
      override def rollback(savepoint: Savepoint): Unit         = conn.rollback(savepoint)
      override def releaseSavepoint(savepoint: Savepoint): Unit = conn.releaseSavepoint(savepoint)
      override def createStatement(i: Int, i1: Int, i2: Int): Statement =
        conn.createStatement(i, i1, i2)
      override def prepareStatement(s: String, i: Int, i1: Int, i2: Int): PreparedStatement =
        conn.prepareStatement(s, i, i1, i2)
      override def prepareCall(s: String, i: Int, i1: Int, i2: Int): CallableStatement =
        conn.prepareCall(s, i, i1, i2)
      override def prepareStatement(s: String, i: Int): PreparedStatement =
        conn.prepareStatement(s, i)
      override def prepareStatement(s: String, ints: Array[Int]): PreparedStatement =
        conn.prepareStatement(s, ints)
      override def prepareStatement(
          s: String,
          strings: Array[String]
      ): PreparedStatement                                     = conn.prepareStatement(s, strings)
      override def createClob(): Clob                          = conn.createClob()
      override def createBlob(): Blob                          = conn.createBlob()
      override def createNClob(): NClob                        = conn.createNClob()
      override def createSQLXML(): SQLXML                      = conn.createSQLXML()
      override def isValid(i: Int): Boolean                    = conn.isValid(i)
      override def setClientInfo(s: String, s1: String): Unit  = conn.setClientInfo(s, s1)
      override def setClientInfo(properties: Properties): Unit = conn.setClientInfo(properties)
      override def getClientInfo(s: String): String            = conn.getClientInfo(s)
      override def getClientInfo: Properties                   = conn.getClientInfo
      override def createArrayOf(s: String, objects: Array[AnyRef]): sql.Array =
        conn.createArrayOf(s, objects)
      override def createStruct(s: String, objects: Array[AnyRef]): Struct =
        conn.createStruct(s, objects)
      override def setSchema(s: String): Unit      = conn.setSchema(s)
      override def getSchema: String               = conn.getSchema
      override def abort(executor: Executor): Unit = conn.abort(executor)
      override def setNetworkTimeout(executor: Executor, i: Int): Unit =
        conn.setNetworkTimeout(executor, i)
      override def getNetworkTimeout: Int                  = conn.getNetworkTimeout
      override def unwrap[T](aClass: Class[T]): T          = conn.unwrap(aClass)
      override def isWrapperFor(aClass: Class[_]): Boolean = conn.isWrapperFor(aClass)
    }
    implicit val cs: ContextShift[IO] =
      IO.contextShift(scala.concurrent.ExecutionContext.Implicits.global)

    // Resource yielding a Transactor[IO] wrapping the given `Connection`
    def transactor(c: Connection): Resource[IO, Transactor[IO]] =
      ExecutionContexts.cachedThreadPool[IO].flatMap { te =>
        val t: Transactor[IO] = Transactor.fromConnection[IO](c, te)
        Resource.liftF(t.configure(_ => IO.pure(t)))
      }

    transactor(connHacked)
      .flatMap { tr =>
        mkDAOResource(tr)
      }
      .use { dao =>
        IO.delay(testCode(dao))
      }
      .unsafeRunSync()
  }
}
