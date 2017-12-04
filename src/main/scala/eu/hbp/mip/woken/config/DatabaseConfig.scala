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

import doobie.imports._

import scalaz.effect.IO
import com.typesafe.scalalogging.slf4j._
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json.deserializationError
import eu.hbp.mip.woken.dao._

import scala.collection.mutable

trait DatabaseConfig[D <: DAL] {

  def dal: D

}

trait DoobieDatabaseConfig[D <: DAL] extends DatabaseConfig[D] {

  def xa: Transactor[IO]

  val logger = Logger(LoggerFactory.getLogger("database"))

  def testConnection(jdbcUrl: String): Unit =
    try {
      sql"select 1".query[Int].unique.transact(xa).unsafePerformIO
    } catch {
      case e: java.sql.SQLException =>
        logger.error(s"Cannot connect to $jdbcUrl", e)
    }

}

/**
  * Configuration for the database storing the results of the calculation launched locally.
  */
case class ResultDatabaseConfig(
    resultDbConnection: DbConnectionConfiguration
) extends DoobieDatabaseConfig[JobResultsDAL] {

  private val jdbcUrl = resultDbConnection.jdbcUrl

  lazy val xa = DriverManagerTransactor[IO](
    resultDbConnection.jdbcDriver,
    jdbcUrl,
    resultDbConnection.jdbcUser,
    resultDbConnection.jdbcPassword
  )

  testConnection(jdbcUrl)

  lazy val dal = new NodeDAL(xa)
}

/**
  * Configuration for the federation database (Denodo) gathering the results from the other nodes.
  */
/*
object FederationDatabaseConfig {
  import WokenConfig._
  import ResultDatabaseConfig.testConnection

  val config: Option[DatabaseConfig[JobResultsDAL]] =
    if (!jobs.jobsConf.hasPath("federationDb")) None
    else
      Some(new DoobieDatabaseConfig[JobResultsDAL] {
        val config = dbConfig(jobs.jobsConf.getString("federationDb"))
        import config._
        lazy val xa = DriverManagerTransactor[IO](
          jdbcDriver,
          jdbcUrl,
          jdbcUser,
          jdbcPassword
        )
        testConnection(xa, jdbcUrl)

        lazy val dal = new FederationDAL(xa)
      })
}
 */

// TODO: move that code to MetaDAL

/**
  * Configuration for the Meta database
  */
case class MetaDatabaseConfig(
    metaDbConnection: DbConnectionConfiguration
) extends DoobieDatabaseConfig[MetaDAL] {

  import spray.json.{ JsArray, JsObject }

  private val jdbcUrl = metaDbConnection.jdbcUrl

  lazy val xa = DriverManagerTransactor[IO](
    metaDbConnection.jdbcDriver,
    jdbcUrl,
    metaDbConnection.jdbcUser,
    metaDbConnection.jdbcPassword
  )

  testConnection(jdbcUrl)

  lazy val dal = new MetaDAL(metaDbConnection)

  // TODO: use a real cache, for example ScalaCache + Caffeine
  val groups: mutable.Map[String, JsObject] = new mutable.WeakHashMap[String, JsObject]() {

    override def get(k: String): Option[JsObject] = {
      var v = super.get(k)
      if (v.isEmpty) {
        val fetch = dal.getMetaData(k)
        put(k, fetch)
        v = Some(fetch)
      }
      v
    }

  }

  def getMetaData(featuresTable: String, variables: Seq[String]): JsObject = {

    /**
      * Parse the tree of groups to find the variables meta data!
      * Temporary... We need to separate groups from variable meta!
      * @return
      */
    def getVariableMetaData(variable: String, groups: JsObject): Option[JsObject] = {

      if (groups.fields.contains("variables")) {
        groups.fields("variables") match {
          case a: JsArray =>
            a.elements.find(
              v =>
                v.asJsObject.fields.get("code") match {
                  case Some(stringValue) => stringValue.convertTo[String] == variable
                  case None              => false
              }
            ) match {
              case Some(value) => return Some(value.asJsObject)
              case None        => None
            }
          case _ => deserializationError("JsArray expected")
        }
      }

      if (groups.fields.contains("groups")) {
        groups.fields("groups") match {
          case a: JsArray =>
            return a.elements.toStream
              .map(g => getVariableMetaData(variable, g.asJsObject))
              .find(o => o.isDefined) match {
              case Some(variable: Option[JsObject]) => variable
              case None                             => None
            }
          case _ => deserializationError("JsArray expected")
        }
      }

      None
    }

    new JsObject(
      variables
        .map(
          v =>
            v -> (getVariableMetaData(v, groups(featuresTable)) match {
              case Some(m) => m
              case None =>
                logger.error(s"Cannot not find metadata for " + v)
                JsObject.empty
            })
        )
        .toMap
    )
  }
}
