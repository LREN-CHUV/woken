package config

import dao.{FederationDAL, NodeDAL, DAL}
import slick.driver.{ H2Driver, JdbcProfile, PostgresDriver }
import slick.jdbc.JdbcBackend._

// TODO: use Slick Database.forConfig() - http://slick.typesafe.com/doc/3.0.0/database.html

trait DatabaseConfig {
  def db: Database
  def profile: JdbcProfile
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
  lazy val db: Database = Database.forURL(jdbcUrl, jdbcUser, jdbcPassword, driver = jdbcDriver)
  lazy val profile: JdbcProfile = jdbcDriver match {
    case "org.postgresql.Driver" => PostgresDriver
    case "org.h2.Driver" => H2Driver
  }
  lazy val dal = new NodeDAL(profile)
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
      lazy val db: Database = Database.forURL(jdbcUrl, jdbcUser, jdbcPassword, driver = jdbcDriver)
      lazy val profile: JdbcProfile = jdbcDriver match {
        case "org.postgresql.Driver" => PostgresDriver
        case "org.h2.Driver" => H2Driver
        case "com.denodo.vdp.jdbc.Driver" => PostgresDriver
      }
      lazy val dal = new FederationDAL(profile)
    })
}
