package config

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

  case class JobServerConf(jobsUrl: String)

  object jobs {
    val jobsConf = config.getConfig("jobs")

    val node = jobsConf.getString("node")
    val owner = jobsConf.getString("owner")
    val chronosServerUrl = jobsConf.getString("chronosServerUrl")
    val resultDb = jobsConf.getString("resultDb")

    import scala.collection.JavaConversions._
    def nodes: Set[String] = jobsConf.getConfig("nodes").entrySet().map(_.getKey())(collection.breakOut)
    def nodeConfig(node: String): JobServerConf = JobServerConf(jobsConf.getConfig(node).getString("jobsUrl"))
  }

  case class DbConfig(
    jdbcDriver: String,
    jdbcJarPath: String,
    jdbcUrl: String,
    jdbcUser: String,
    jdbcPassword: String
  )

  def dbConfig(dbAlias: String): DbConfig = {
    val dbConf = config.getConfig("db").getConfig(dbAlias)
    new DbConfig(
      jdbcDriver = dbConf.getString("jdbc_driver"),
      jdbcJarPath = dbConf.getString("jdbc_jar_path"),
      jdbcUrl = dbConf.getString("jdbc_url"),
      jdbcUser = dbConf.getString("jdbc_user"),
      jdbcPassword = dbConf.getString("jdbc_password")
    )
  }
}
