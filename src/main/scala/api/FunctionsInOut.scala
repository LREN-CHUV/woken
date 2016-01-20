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

  def query2job(query: Query): JobDto = {
    val jobId = UUID.randomUUID().toString
    val requestConfig = Config.defaults.getConfig("request")
    val dockerImage = requestConfig.getConfig("functions").getString(query.request.plot)
    val defaultDb = requestConfig.getString("inDb")
    val mainTable = requestConfig.getString("mainTable")
    val parameters = Map[String, String](
      "PARAM_query" -> s"select ${query.variables.map(_.code).mkString(",")} from $mainTable",
      "PARAM_colnames" -> query.variables.map(_.code).mkString(",")
    )

    JobDto(jobId, dockerImage, None, None, Some(defaultDb), parameters, None)
  }

  lazy val summaryStatsHeader = JsonParser(""" [["min","q1","median","q3","max","mean","std","sum","count"]] """)

  // Left Dataset indicates an error
  def summaryStatsResult2Dataset(result: JobResult): Either[Dataset, Dataset] = {

    result.data.map { data =>
      val json = JsonParser(data).asJsObject
      val correctedData = json.fields.mapValues {
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
      case res :: Nil => summaryStatsResult2Dataset(res)
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

