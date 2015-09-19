package utils

import com.typesafe.config.ConfigFactory


object Config {
  private val config =  ConfigFactory.load()

  object app {
    val appConf = config.getConfig("app")

    val systemName = appConf.getString("systemName")
    val interface = appConf.getString("interface")
    val port = appConf.getInt("port")
    val jobServiceName = appConf.getString("jobServiceName")

  }

  object jobs {
    val jobsConf = config.getConfig("jobs")

    val node = jobsConf.getString("node")
    val owner = jobsConf.getString("owner")
    val chronosServerUrl = jobsConf.getString("chronosServerUrl")
  }

  case class DbConfig(
    jdbcDriver: String,
    jdbcJarPath: String,
    jdbcUrl: String,
    jdbcUser: String,
    jdbcPassword: String
  )

  def getDbConfig(dbAlias: String): DbConfig = {
    val dbConfig = config.getConfig(dbAlias)
    new DbConfig(
      jdbcDriver = dbConfig.getString("jdbc_driver"),
      jdbcJarPath = dbConfig.getString("jdbc_jar_path"),
      jdbcUrl = dbConfig.getString("jdbc_url"),
      jdbcUser = dbConfig.getString("jdbc_user"),
      jdbcPassword = dbConfig.getString("jdbc_password")
    )
  }
}
