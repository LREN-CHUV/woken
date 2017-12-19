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

package eu.hbp.mip.woken.config

import doobie._
import doobie.implicits._
import doobie.hikari.HikariTransactor
import cats._
import cats.implicits._
import cats.effect._
import cats.data.Validated._
import com.typesafe.config.Config
import eu.hbp.mip.woken.cromwell.core.ConfigUtil._

import scala.language.higherKinds

final case class DatabaseConfiguration(dbiDriver: String,
                                       jdbcDriver: String,
                                       jdbcUrl: String,
                                       host: String,
                                       port: Int,
                                       user: String,
                                       password: String)

object DatabaseConfiguration {

  def read(config: Config, path: List[String]): Validation[DatabaseConfiguration] = {
    val dbConfig = config.validateConfig(path.mkString("."))

    dbConfig.andThen { db =>
      val dbiDriver: Validation[String] =
        db.validateString("dbi_driver").orElse(lift("PostgreSQL"))
      val jdbcDriver: Validation[String] =
        db.validateString("jdbc_driver").orElse(lift("org.postgresql.Driver"))
      val jdbcUrl  = db.validateString("jdbc_url")
      val host     = db.validateString("host")
      val port     = db.validateInt("port")
      val user     = db.validateString("user")
      val password = db.validateString("password")

      (dbiDriver, jdbcDriver, jdbcUrl, host, port, user, password) mapN DatabaseConfiguration.apply
    }
  }

  def factory(config: Config): String => Validation[DatabaseConfiguration] =
    dbAlias => read(config, List("db", dbAlias))

  def dbTransactor(dbConfig: DatabaseConfiguration): IO[HikariTransactor[IO]] =
    for {
      xa <- HikariTransactor[IO](dbConfig.jdbcDriver,
                                 dbConfig.jdbcUrl,
                                 dbConfig.user,
                                 dbConfig.password)
      _ <- xa.configure(hx => IO(hx.setAutoCommit(false)))
    } yield xa

  // TODO: it should become Validated[]
  def testConnection[F[_]: Monad](xa: Transactor[F]): F[Int] =
    sql"select 1".query[Int].unique.transact(xa)

}
