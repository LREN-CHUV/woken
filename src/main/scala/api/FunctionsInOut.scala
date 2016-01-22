package api

import java.time.OffsetDateTime
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
  private[this] lazy val requestConfig = Config.defaultSettings.getConfig("request")
  private[this] lazy val mainTable = requestConfig.getString("mainTable")

  /** Convert variable to lowercase as Postgres returns lowercase fields in its result set
    * ADNI variables have been adjusted to be valid field names using the following conversions:
    * + replace - by _
    * + prepend _ to the variable name if it starts by a number
    */
  private[this] val toField = (v: VariableId) => v.code.toLowerCase().replaceAll("-", "_").replaceFirst("^(\\d)", "_$1")

  // TODO: filter is never used
  private[this] val selectQueryVariable = (query: Query) => Map[String, String] (
    "PARAM_query" -> s"select ${query.variables.map(toField).mkString(",")} from $mainTable",
    "PARAM_colnames" -> query.variables.map(toField).mkString(",")
  )

  // TODO: grouping is never used, nor filter
  private[this] val linerarRegressionParameters = (query: Query) => Map[String, String] (
    "PARAM_query" -> s"select ${(query.variables ++ query.covariables).map(toField).mkString(",")} from $mainTable",
    "PARAM_varname" -> query.variables.map(toField).mkString(","),
    "PARAM_covarnames" -> query.covariables.map(toField).mkString(",")
  )

  val queryToParameters: Map[String, Query => Map[String, String]] = Map(
    "boxplot" -> selectQueryVariable,
    "linearregression" -> linerarRegressionParameters
  )

  val resultToDataset: Map[String, JobResult => Either[Dataset, Dataset]] = Map(
    "r-summary-stats" -> summaryStatsResult2Dataset,
    "r-linear-regression" -> linearRegressionResult2Dataset
  )

  def query2job(query: Query): JobDto = {

    val jobId = UUID.randomUUID().toString
    val dockerImage = requestConfig.getConfig("functions").getString(query.request.plot.toLowerCase())
    val defaultDb = requestConfig.getString("inDb")
    val parameters = queryToParameters(query.request.plot.toLowerCase)(query)

    JobDto(jobId, dockerImage, None, None, Some(defaultDb), parameters, None)
  }

  lazy val summaryStatsHeader = JsonParser(""" [["min","q1","median","q3","max","mean","std","sum","count"]] """)

  // Left Dataset indicates an error
  def summaryStatsResult2Dataset(result: JobResult): Either[Dataset, Dataset] = {

    result.data.map { data =>
      val json = JsonParser(data).asJsObject
      val correctedData = json.fields.filterKeys(_ != "_row").mapValues {
        case JsArray(values) => JsArray(values.flatMap {
          case JsArray(nested) => nested
          case simple => Vector(simple)
        })
        case _ => throw new IllegalArgumentException("[Summary stats] Unexpected json format: " + data)
      }
      Right(Dataset(result.jobId, result.timestamp, summaryStatsHeader, JsObject(correctedData)))
    } getOrElse
      Left(Dataset(result.jobId, result.timestamp, JsArray(), JsString(result.error.getOrElse("unknown error"))))
  }

  // Left Dataset indicates an error
  def linearRegressionResult2Dataset(result: JobResult): Either[Dataset, Dataset] = {

    result.data.map { data =>
      val json = JsonParser(data).asJsObject
      val coefficients = json.fields("coefficients")
      val residuals = json.fields("residuals").asJsObject()
      val columns = residuals.fields("_row")
      val header = JsObject(
        "coefficients" -> columns,
        "residuals" -> JsArray(columns)
      )
      val dataset = JsObject(
        "coefficients" -> coefficients,
        "residuals" -> JsObject(residuals.fields.filterKeys(_ != "_row"))
      )
      Right(Dataset(result.jobId, result.timestamp, header, dataset))
    } getOrElse
      Left(Dataset(result.jobId, result.timestamp, JsArray(), JsString(result.error.getOrElse("unknown error"))))
  }

}

case class DatasetResults(dataset: Dataset) extends RestMessage {
  import DatasetResults._
  import spray.httpx.SprayJsonSupport._
  override def marshaller: ToResponseMarshaller[DatasetResults] = ToResponseMarshaller.fromMarshaller(StatusCodes.OK)(sprayJsonMarshaller(datasetResultsFormat))
}

object DatasetResults extends DefaultJsonProtocol with JobResults.Factory {

  def apply(results: scala.collection.Seq[JobResult]) = {
    import FunctionsInOut._

    val datasetAdapted: Either[Dataset, Dataset] = results match {
      case res :: Nil => resultToDataset(res.function)(res)
      case res :: _   => Left(Dataset(res.jobId, res.timestamp, JsArray(), JsString(s"Expected one job result, got ${results.length}")))
      case _          => Left(Dataset("", OffsetDateTime.now(), JsArray(), JsString(s"No results returned")))
    }

    datasetAdapted.fold(DatasetResults(_) , DatasetResults(_)): DatasetResults
  }

  import ApiJsonSupport._

  implicit object datasetResultsFormat extends RootJsonFormat[DatasetResults] {
    override def write(r: DatasetResults) = datasetJsonFormat.write(r.dataset)
    override def read(json: JsValue): DatasetResults = throw new NotImplementedError("Cannot read a DatasetResult")
  }
}

