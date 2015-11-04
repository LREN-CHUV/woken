package dao

import core.model.JobResult

/**
  * JobResultComponent provides database definitions for JobResult objects
  */
trait JobResultComponent { this: DriverComponent =>
  import driver.api._

  class JobResults(tag: Tag) extends Table[JobResult](tag, "job_result") {
    def requestId: Rep[String] = column[String]("request_id")
    def node: Rep[String] = column[String]("node")
    def timestamp: Rep[Long] = column[Long]("timestamp")
    def data: Rep[Option[String]] = column[Option[String]]("data")
    def error: Rep[Option[String]] = column[Option[String]]("error")

    def pk = primaryKey("pk_job_result", requestId)
    def * = (requestId, node, timestamp, data, error) <>((JobResult.apply _).tupled, JobResult.unapply)
  }

  val jobResults = TableQuery[JobResults]

  def createJobResults() = jobResults.schema.create

  def getJobResults(requestId: String): DBIO[Seq[JobResult]] = jobResults.filter(_.requestId === requestId).result

}
