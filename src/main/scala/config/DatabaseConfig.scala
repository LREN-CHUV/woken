package config

import dao.{FederationDAL, NodeDAL, DAL}
import doobie.imports._
import scalaz.effect.IO

trait DatabaseConfig {
  def xa: Transactor[IO]
  def dal: DAL
}

/**
  * Configuration for the database storing the results of the calculation launched locally.
  */
//Based on play-slick driver loader
object ResultDatabaseConfig extends DatabaseConfig {
  import Config._
  val config = dbConfig(jobs.resultDb)
  import config._
  lazy val xa = DriverManagerTransactor[IO](
    jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword
  )
  lazy val dal = new NodeDAL(xa)
}

/**
  * Configuration for the federation database (Denodo) gathering the results from the other nodes.
  */
//Based on play-slick driver loader
object FederationDatabaseConfig {
  import Config._

  val config: Option[DatabaseConfig] = if (!jobs.jobsConf.hasPath("federationDb")) None else
    Some(new DatabaseConfig {
      val config = dbConfig(jobs.jobsConf.getString("federationDb"))
      import config._
      lazy val xa = DriverManagerTransactor[IO](
        jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword
      )
      lazy val dal = new FederationDAL(xa)
    })
}
