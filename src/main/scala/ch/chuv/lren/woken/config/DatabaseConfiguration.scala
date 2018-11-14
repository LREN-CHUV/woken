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

package ch.chuv.lren.woken.config

import doobie._
import doobie.implicits._
import doobie.hikari._
import cats.implicits._
import cats.effect._
import cats.data.Validated._
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn }
import com.typesafe.config.Config
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.fp.Traverse
import ch.chuv.lren.woken.messages.variables.SqlType

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

/**
  * Connection configuration for a database
  *
  * @param dbiDriver R DBI driver, default to PostgreSQL
  * @param dbApiDriver Python DBAPI driver, default to postgresql
  * @param jdbcDriver Java JDBC driver, default to org.postgresql.Driver
  * @param jdbcUrl Java JDBC URL
  * @param host Database host
  * @param port Database port
  * @param database Name of the database, default to the user name
  * @param user Database user
  * @param password Database password
  */
final case class DatabaseConfiguration(dbiDriver: String,
                                       dbApiDriver: String,
                                       jdbcDriver: String,
                                       jdbcUrl: String,
                                       host: String,
                                       port: Int,
                                       database: String,
                                       user: String,
                                       password: String,
                                       poolSize: Int,
                                       tables: Set[FeaturesTableDescription])

object DatabaseConfiguration {

  def read(config: Config, path: List[String]): Validation[DatabaseConfiguration] = {

    val dbConfig = config.validateConfig(path.mkString("."))

    dbConfig.andThen { db =>
      val dbiDriver: Validation[String] =
        db.validateString("dbi_driver").orElse(lift("PostgreSQL"))
      val dbApiDriver: Validation[String] =
        db.validateString("dbapi_driver").orElse(lift("postgresql"))
      val jdbcDriver: Validation[String] =
        db.validateString("jdbc_driver").orElse(lift("org.postgresql.Driver"))
      val jdbcUrl                      = db.validateString("jdbc_url")
      val host                         = db.validateString("host")
      val port                         = db.validateInt("port")
      val user                         = db.validateString("user")
      val password                     = db.validateString("password")
      val database: Validation[String] = db.validateString("database")
      val poolSize: Validation[Int] =
        db.validateInt("pool_size").orElse(lift(10))

      val tableNames: Validation[Set[String]] = db
        .validateConfig("tables")
        .map(_.keys)
        .orElse(lift(Set()))

      val tableFactory: String => Validation[FeaturesTableDescription] =
        table => readTable(db, List("tables", table), path.last)

      val tables: Validation[Set[FeaturesTableDescription]] = {
        tableNames.andThen { names: Set[String] =>
          val m: Set[Validation[FeaturesTableDescription]] = names.map(tableFactory)
          Traverse.sequence(m)
        }
      }

      (dbiDriver,
       dbApiDriver,
       jdbcDriver,
       jdbcUrl,
       host,
       port,
       database,
       user,
       password,
       poolSize,
       tables) mapN DatabaseConfiguration.apply
    }
  }

  private def readTable(config: Config,
                        path: List[String],
                        database: String): Validation[FeaturesTableDescription] = {
    val tableConfig = config.validateConfig(path.mkString("."))

    tableConfig.andThen { table =>
      val tableName = path.lastOption.map(lift).getOrElse("Empty path".invalidNel[String])
      val primaryKey: Validation[List[TableColumn]] = table
        .validateConfigList("primaryKey")
        .andThen { cl =>
          cl.map { col =>
              val name: Validation[String] = col.validateString("name")
              val sqlType: Validation[SqlType.Value] =
                col.validateString("sqlType").map(SqlType.withName)

              (name, sqlType) mapN TableColumn
            }
            .traverse[Validation, TableColumn](identity)
        }
      val datasetColumn: Validation[Option[TableColumn]] = table
        .validateConfig("datasetColumn")
        .andThen { col =>
          val name: Validation[String] = col.validateString("name")
          val sqlType: Validation[SqlType.Value] =
            col.validateString("sqlType").map(SqlType.withName)
          val validatedCol: Validation[TableColumn] = (name, sqlType) mapN TableColumn
          val s: Validation[Option[TableColumn]]    = validatedCol.map(Option.apply)
          s
        }
        .orElse(lift(None))
      val schema: Validation[Option[String]] = table.validateOptionalString("schema")
      val seed: Validation[Double]           = table.validateDouble("seed").orElse(lift(0.67))

      (lift(database), schema, tableName, primaryKey, datasetColumn, lift(None), seed) mapN FeaturesTableDescription
    }
  }

  def factory(config: Config): String => Validation[DatabaseConfiguration] =
    dbAlias => read(config, List("db", dbAlias))

  def dbTransactor(dbConfig: DatabaseConfiguration): Resource[IO, HikariTransactor[IO]] = {

    // We need a ContextShift[IO] before we can construct a Transactor[IO]. The passed ExecutionContext
    // is where nonblocking operations will be executed.
    implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
    for {
      // our connect EC
      ce <- ExecutionContexts.fixedThreadPool[IO](2)
      // our transaction EC
      te <- ExecutionContexts.cachedThreadPool[IO]

      xa <- HikariTransactor.newHikariTransactor[IO](driverClassName = dbConfig.jdbcDriver,
                                                     url = dbConfig.jdbcUrl,
                                                     user = dbConfig.user,
                                                     pass = dbConfig.password,
                                                     connectEC = ce,
                                                     transactEC = te)
      _ <- Resource.liftF {
        xa.configure(
          hx =>
            Async[IO].delay {
              hx.getHikariConfigMXBean.setMaximumPoolSize(dbConfig.poolSize)
              hx.setAutoCommit(false)
          }
        )
      }

      validatedXa <- Resource.liftF {
        for {
          test <- sql"select 1".query[Int].unique.transact(xa)
        } yield {
          if (test != 1) "Cannot connect to $dbConfig.jdbcUrl".invalidNel[HikariTransactor[IO]]
          else lift(xa)
        }
      }

    } yield xa
  }
  // TODO: .memoize (using Monix?)

  def validate(xa: HikariTransactor[IO],
               dbConfig: DatabaseConfiguration): IO[Validation[HikariTransactor[IO]]] =
    for {
      test <- sql"select 1".query[Int].unique.transact(xa)
    } yield {
      if (test != 1) "Cannot connect to $dbConfig.jdbcUrl".invalidNel[HikariTransactor[IO]]
      else lift(xa)
    }
}
