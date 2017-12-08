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
import com.typesafe.scalalogging.slf4j.Logger
import eu.hbp.mip.woken.cromwell.core.ConfigUtil._
import org.slf4j.LoggerFactory

import scala.language.higherKinds

final case class DatabaseConfiguration(jdbcDriver: String,
                                       jdbcUrl: String,
                                       user: String,
                                       password: String)

object DatabaseConfiguration {

  def read(config: Config, path: Seq[String]): Validation[DatabaseConfiguration] = {
    val jdbcConfig = path.foldLeft(config) { (c, s) =>
      c.getConfig(s)
    }

    val jdbcDriver   = jdbcConfig.validateString("jdbc_driver")
    val jdbcUrl      = jdbcConfig.validateString("jdbc_url")
    val jdbcUser     = jdbcConfig.validateString("user")
    val jdbcPassword = jdbcConfig.validateString("password")

    (jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword) mapN DatabaseConfiguration.apply
  }

  def factory(config: Config): String => Validation[DatabaseConfiguration] =
    dbAlias => read(config, List("db", dbAlias))

  def dbTransactor[F[_]: Async](dbConfig: DatabaseConfiguration): F[HikariTransactor[F]] =
    HikariTransactor[F](dbConfig.jdbcDriver, dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)

  val logger = Logger(LoggerFactory.getLogger("database"))
  // TODO: it should become Validated[]
  def testConnection[F[_]: Monad](xa: Transactor[F], dbConfig: DatabaseConfiguration): Unit =
    try {
      sql"select 1".query[Int].unique.transact(xa).unsafePerformIO
    } catch {
      case e: java.sql.SQLException =>
        logger.error(s"Cannot connect to ${dbConfig.jdbcUrl}", e)
    }

}
