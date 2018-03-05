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
import doobie.hikari.HikariTransactor
import cats.Monad
import cats.implicits._
import cats.effect._
import cats.data.Validated._
import com.typesafe.config.Config
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._

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
                                       password: String)

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
      val database: Validation[String] = db.validateString("database").orElse(user)

      (dbiDriver, dbApiDriver, jdbcDriver, jdbcUrl, host, port, database, user, password) mapN DatabaseConfiguration.apply
    }
  }

  def factory(config: Config): String => Validation[DatabaseConfiguration] =
    dbAlias => read(config, List("db", dbAlias))

  def dbTransactor(dbConfig: DatabaseConfiguration): IO[HikariTransactor[IO]] =
    for {
      xa <- HikariTransactor.newHikariTransactor[IO](dbConfig.jdbcDriver,
                                                     dbConfig.jdbcUrl,
                                                     dbConfig.user,
                                                     dbConfig.password)
      _ <- xa.configure(hx => IO(hx.setAutoCommit(false)))
    } yield xa

  // TODO: it should become Validated[]
  def testConnection[F[_]: Monad](xa: Transactor[F]): F[Int] =
    sql"select 1".query[Int].unique.transact(xa)

}
