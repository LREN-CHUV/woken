package eu.hbp.mip.woken.config

import java.time.{ZoneId, Instant, OffsetDateTime}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterEach

import eu.hbp.mip.woken.core.model.JobResult

object DatabaseSupportSpec {

  private def ofEpochMilli(epochMilli: Long) = OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.of("GMT"))
  lazy val jobResults = Seq(
    JobResult("001", "ldsm1", ofEpochMilli(1446216571000L), Some(""" [1,2,3] """), None, "", ""),
    JobResult("002", "ldsm1", ofEpochMilli(1446216571000L), Some(""" [2,4,6] """), None, "", ""),
    JobResult("003", "ldsm1", ofEpochMilli(1446216571000L), Some(""" [3,6,9] """), None, "", "")
  )

}

trait SpecSupport extends Specification with BeforeAfterEach {

  import ResultDatabaseConfig._
  //import ResultDatabaseConfig.profile.api._

  def createSchema = {
    /*
    val dropAll = dal.jobResults.schema.drop

    val createAll =
      DBIO.seq(
        dal.jobResults.schema.create,
        dal.jobResults ++= DatabaseSupportSpec.jobResults
      )

    val results = db.run(MTable.getTables).flatMap {
      tables => if (tables.toList.size > 1) {
        db.run(dropAll).flatMap(_ => db.run(createAll))
      } else db.run(createAll)
    }
    Await.result(results, Duration.Inf)
    */

  }

  override def before: Unit= {
    createSchema
  }

  override def after: Unit= { }
}

