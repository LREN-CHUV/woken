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

package eu.hbp.mip.woken.api

import java.util.UUID

import eu.hbp.mip.woken.backends.DockerJob
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json._
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.config.WokenConfig
import eu.hbp.mip.woken.config.MetaDatabaseConfig
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.core.{ ExperimentActor, JobResults, RestMessage }
import org.slf4j.LoggerFactory

/**
  * Transformations for input and output values of functions
  */
object FunctionsInOut {
  import WokenConfig.defaultSettings._

  implicit class QueryEnhanced(query: Query) {

    /** Convert variable to lowercase as Postgres returns lowercase fields in its result set
      * Variables codes are sanitized to ensure valid database field names using the following conversions:
      * + replace - by _
      * + prepend _ to the variable name if it starts by a number
      */
    private[this] val toField = (v: VariableId) =>
      v.code.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1")

    def dbAllVars: List[String] =
      (query.variables ++ query.covariables ++ query.grouping).distinct.map(toField).toList

    def dbVariables: List[String]   = query.variables.map(toField).toList
    def dbCovariables: List[String] = query.covariables.map(toField).toList
    def dbGrouping: List[String]    = query.grouping.map(toField).toList

  }

  def query2job(query: MiningQuery): DockerJob = {

    val jobId    = UUID.randomUUID().toString
    val metadata = MetaDatabaseConfig.getMetaData(query.dbAllVars)

    DockerJob(jobId,
              dockerImage(query.algorithm.code),
              defaultDb,
              mainTable,
              query,
              metadata = metadata)
  }

  def query2job(query: ExperimentQuery): ExperimentActor.Job = {

    val jobId    = UUID.randomUUID().toString
    val metadata = MetaDatabaseConfig.getMetaData(query.dbAllVars)

    ExperimentActor.Job(jobId, defaultDb, mainTable, query, metadata = metadata)
  }

  lazy val summaryStatsHeader = JsonParser(
    """ [["min","q1","median","q3","max","mean","std","sum","count"]] """
  )

}

case class JsonMessage(json: JsValue) extends RestMessage {
  import spray.httpx.SprayJsonSupport._
  import ApiJsonSupport._
  val JsonFormat: RootJsonFormat[JsonMessage] = lift(new RootJsonWriter[JsonMessage] {
    override def write(obj: JsonMessage): JsValue = JsValueFormat.write(json)
  })
  override def marshaller: ToResponseMarshaller[JsonMessage] =
    ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(sprayJsonMarshaller(JsonFormat))
}

object RequestProtocol extends DefaultJsonProtocol with JobResults.Factory {

  private[this] val log = LoggerFactory.getLogger(this.getClass.getName)

  def apply(results: scala.collection.Seq[JobResult]): RestMessage = {
    import ApiJsonSupport._

    log.debug(s"Received job results:  $results")

    results match {
      case res :: Nil =>
        res.shape match {
          case "pfa_yaml" =>
            val json = yaml2Json(Yaml(res.data.getOrElse("'No results returned'")))
            JsonMessage(json)

          case "pfa_json" =>
            val str  = res.data.getOrElse("'No results returned'")
            val json = JsonParser(str)
            JsonMessage(json)

          case "application/highcharts+json" =>
            val str  = res.data.getOrElse("'No results returned'")
            val json = JsonParser(str)
            JsonMessage(json)
        }
    }
  }
}
