package config

import slick.driver.{ H2Driver, JdbcProfile, PostgresDriver }
import slick.jdbc.JdbcBackend._

//Based on play-slick driver loader
object DatabaseConfig {
  import Config._
  val config = dbConfig(jobs.resultDb)
  import config._
  lazy val db: Database = Database.forURL(jdbcUrl, jdbcUser, jdbcPassword, driver = jdbcDriver)
  lazy val profile: JdbcProfile = jdbcDriver match {
    case "org.postgresql.Driver" => PostgresDriver
    case "org.h2.Driver" => H2Driver
  }
}
