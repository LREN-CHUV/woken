package config

import dao.JobResultDao
import core.model.results.JobResult
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterEach
import slick.jdbc.meta.MTable
import DatabaseConfig._
import DatabaseConfig.profile.api._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object DatabaseSupportSpec {

  lazy val jobResults = Seq(
    JobResult("001", "ldsm1", 1446216571000L, Some(""" [1,2,3] """)),
    JobResult("002", "ldsm1", 1446216571000L, Some(""" [2,4,6] """)),
    JobResult("003", "ldsm1", 1446216571000L, Some(""" [3,6,9] """))
  )
}

trait SpecSupport extends Specification with BeforeAfterEach {

  def createSchema = {
    val dropAll = JobResultDao.jobResults.schema.drop

    val createAll =
      DBIO.seq(
        JobResultDao.jobResults.schema.create,
        JobResultDao.jobResults ++= DatabaseSupportSpec.jobResults
      )

    val results = db.run(MTable.getTables).flatMap {
      tables => if (tables.toList.size > 1) {
        db.run(dropAll).flatMap(_ => db.run(createAll))
      } else db.run(createAll)
    }

    Await.result(results, Duration.Inf)
  }

  override def before: Unit= {
    createSchema
  }

  override def after: Unit= { }
}

