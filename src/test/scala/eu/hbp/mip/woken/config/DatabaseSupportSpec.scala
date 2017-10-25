package eu.hbp.mip.woken.config

import java.time.{Instant, OffsetDateTime, ZoneId}

import eu.hbp.mip.woken.core.model.JobResult
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterEach

object DatabaseSupportSpec {

  private def ofEpochMilli(epochMilli: Long) = OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.of("GMT"))
  lazy val jobResults = Seq(
    JobResult("001", "ldsm1", ofEpochMilli(1446216571000L), Some(""" [1,2,3] """), None, "", ""),
    JobResult("002", "ldsm1", ofEpochMilli(1446216571000L), Some(""" [2,4,6] """), None, "", ""),
    JobResult("003", "ldsm1", ofEpochMilli(1446216571000L), Some(""" [3,6,9] """), None, "", "")
  )

}

trait SpecSupport extends Specification with BeforeAfterEach {
  //import ResultDatabaseConfig.profile.api._

  def createSchema(): Unit = {
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
    createSchema()
  }

  override def after: Unit= { }
}
