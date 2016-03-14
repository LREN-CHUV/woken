package api

import java.util.UUID

import config.Config
import core.model.JobResult
import core.{JobResults, RestMessage}
import spray.http.StatusCodes
import spray.httpx.marshalling.ToResponseMarshaller
import spray.json._

/**
  * Transformations for input and output values of functions
  */
object FunctionsInOut {
  import Config.defaultSettings._

  /** Convert variable to lowercase as Postgres returns lowercase fields in its result set
    * ADNI variables have been adjusted to be valid field names using the following conversions:
    * + replace - by _
    * + prepend _ to the variable name if it starts by a number
    */
  private[this] val toField = (v: VariableId) => v.code.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1")

  // TODO: filter is never used
  private[this] val standardParameters = (query: Query) => Map[String, String] (
    "PARAM_query" -> s"select ${(query.variables ++ query.covariables ++ query.grouping).distinct.map(toField).mkString(",")} from $mainTable",
    "PARAM_variables" -> query.variables.map(toField).mkString(","),
    "PARAM_covariables" -> query.covariables.map(toField).mkString(","),
    "PARAM_grouping" -> query.grouping.map(toField).mkString(",")
  )

  val queryToParameters: Map[String, Query => Map[String, String]] = Map(
    "boxplot" -> standardParameters,
    "linearregression" -> standardParameters
  ).withDefaultValue(standardParameters)

  def query2job(query: Query): JobDto = {

    val jobId = UUID.randomUUID().toString
    val parameters = queryToParameters(query.algorithm.toLowerCase)(query)

    JobDto(jobId, dockerImage(query.algorithm), None, None, Some(defaultDb), parameters, None)
  }

  lazy val summaryStatsHeader = JsonParser(""" [["min","q1","median","q3","max","mean","std","sum","count"]] """)

}

case class JsonMessage(json: JsValue) extends RestMessage {
  import spray.httpx.SprayJsonSupport._
  import ApiJsonSupport._
  val JsonFormat = lift(new RootJsonWriter[JsonMessage] {
    override def write(obj: JsonMessage): JsValue = JsValueFormat.write(json)
  })
  override def marshaller: ToResponseMarshaller[JsonMessage] = ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(sprayJsonMarshaller(JsonFormat))
}

object RequestProtocol extends DefaultJsonProtocol with JobResults.Factory {

  import ApiJsonSupport._

  def apply(results: scala.collection.Seq[JobResult]): RestMessage = {
    print (results)

    results match {
      case res :: Nil => res.shape match {
        case "pfa_yaml" => {
          val json = yaml2Json(Yaml(res.data.getOrElse("'No results returned'")))
          JsonMessage(json)
        }
        case "pfa_json" => {
          val str = res.data.getOrElse("'No results returned'")
          val json = JsonParser(str)
          JsonMessage(json)
        }
      }
    }
  }
}

