package config

import com.typesafe.config.ConfigFactory
import cromwell.util.ConfigUtil._

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
    val ldsmDb = jobsConf.getStringOption("ldsmDb")
    val federationDb = jobsConf.getStringOption("federationDb")
    val resultDb = jobsConf.getString("resultDb")
    val nodesConf = jobsConf.getConfigOption("nodes")

    import scala.collection.JavaConversions._
    def nodes: Set[String] = nodesConf.fold(Set[String]())(c => c.entrySet().map(_.getKey.takeWhile(_ != '.'))(collection.breakOut))
    def nodeConfig(node: String): JobServerConf = JobServerConf(nodesConf.get.getConfig(node).getString("jobsUrl"))
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

  object defaultSettings {
    val defaultSettingsConf = config.getConfig("defaultSettings")
    lazy val requestConfig = defaultSettingsConf.getConfig("request")
    lazy val mainTable = requestConfig.getString("mainTable")
    def dockerImage(plot: String) = requestConfig.getConfig("functions").getConfig(plot.toLowerCase()).getString("image")
    def isPredictive(plot: String) = requestConfig.getConfig("functions").getConfig(plot.toLowerCase()).getBoolean("predictive")
    val defaultDb = requestConfig.getString("inDb")
  }
}
