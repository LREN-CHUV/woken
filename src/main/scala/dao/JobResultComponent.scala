package dao

import core.model.JobResult

/**
  * JobResultComponent provides database definitions for JobResult objects
  */
trait JobResultComponent { this: DriverComponent =>
  import driver.api._

  class JobResults(tag: Tag) extends Table[JobResult](tag, "job_result") {
    def jobId: Rep[String] = column[String]("job_id")
    def node: Rep[String] = column[String]("node")
    def timestamp: Rep[Long] = column[Long]("timestamp")
    def data: Rep[Option[String]] = column[Option[String]]("data")
    def error: Rep[Option[String]] = column[Option[String]]("error")

    def pk = primaryKey("pk_job_result", jobId)
    def * = (jobId, node, timestamp, data, error) <>((JobResult.apply _).tupled, JobResult.unapply)
  }

  val jobResults = TableQuery[JobResults]

  def createJobResults() = jobResults.schema.create

  def getJobResults(jobId: String): DBIO[Seq[JobResult]] = jobResults.filter(_.jobId === jobId).result

}
