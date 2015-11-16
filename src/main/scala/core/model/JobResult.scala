package core.model

import spray.json.DefaultJsonProtocol

case class JobResult(jobId: String, node: String, timestamp: Long,
                      data: Option[String] = None, error: Option[String] = None) {

}

object JobResult extends DefaultJsonProtocol {
  implicit val jobResultFormat = lazyFormat(jsonFormat5(JobResult.apply))
}