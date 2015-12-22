package api

import core.model.JobResult
import core.Error
import spray.json.{JsObject, JsArray, JsonParser}

/**
  * Transformations for input and output values of functions
  */
object FunctionsInOut {

  def query2job(query: Query): JobDto = ???

  lazy val summaryStatsHeader = JsonParser(""" [["min","q1","median","q3","max","mean","std","sum","count"]] """)

  def summaryStatsResult2Dataset(result: JobResult): Either[Error, Dataset] = {

    result.data.map { data =>
      val json = JsonParser(data).asJsObject
      val correctedData = json.fields.mapValues {
        case JsArray(values) => JsArray(values.flatMap {
          case JsArray(nested) => nested
          case simple => Vector(simple)
        })
        case _ => throw new IllegalArgumentException("[Summary stats] Unexpected json format: " + data)
      }
      Right[Error, Dataset](Dataset(result.jobId, result.timestamp, summaryStatsHeader, JsObject(correctedData)))
    } getOrElse
      Left[Error, Dataset](Error(result.error.getOrElse("unknown error")))

  }

}
