package dao

import core.model.results.JobResult
import config.DatabaseConfig._
import profile.api._

trait JobResultDao {

  def create()

  def get(requestId: String): DBIO[Seq[JobResult]]

}

trait JobResultDaoSlickImpl extends JobResultDao {

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

  override def create() = jobResults.schema.create

  override def get(requestId: String): DBIO[Seq[JobResult]] = jobResults.filter(_.requestId === requestId).result

}

object JobResultDao extends JobResultDaoSlickImpl

