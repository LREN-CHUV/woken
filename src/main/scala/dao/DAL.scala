package dao

import java.time.{ZoneOffset, LocalDateTime, OffsetDateTime}

import core.model.JobResult
import doobie.imports._

import scalaz.effect.IO

/**
  * Created by ludovic on 25/11/15.
  */
trait DAL {

  def findJobResults(jobId: String): List[JobResult]

}

object DAL {
  implicit val DateTimeMeta: Meta[OffsetDateTime] =
    Meta[java.sql.Timestamp].nxmap(
      ts => OffsetDateTime.of(ts.toLocalDateTime, ZoneOffset.UTC),
      dt => java.sql.Timestamp.valueOf(dt.toLocalDateTime)
    )
}

class NodeDAL(xa: Transactor[IO]) extends DAL {
  import DAL._

  def queryJobResults(jobId: String): ConnectionIO[List[JobResult]] = sql"select job_id, node, timestamp, data, error from job_result where job_id = $jobId".query[JobResult].list

  override def findJobResults(jobId: String) = queryJobResults(jobId).transact(xa).unsafePerformIO

}

class FederationDAL(xa: Transactor[IO]) extends DAL {
  import DAL._

  def queryJobResults(jobId: String): ConnectionIO[List[JobResult]] = sql"select job_id, node, timestamp, data, error from job_result_nodes where job_id = $jobId".query[JobResult].list

  override def findJobResults(jobId: String) = queryJobResults(jobId).transact(xa).unsafePerformIO

}