/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.config

import java.time.{ Instant, OffsetDateTime, ZoneId }

import eu.hbp.mip.woken.core.model.JobResult
import org.scalatest._

object DatabaseSupportSpec {

  private def ofEpochMilli(epochMilli: Long) =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.of("GMT"))
//  lazy val jobResults = Seq(
//    JobResult("001", "ldsm1", ofEpochMilli(1446216571000L), "", "", Some(""" [1,2,3] """), None),
//    JobResult("002", "ldsm1", ofEpochMilli(1446216571000L), "", "", Some(""" [2,4,6] """), None),
//    JobResult("003", "ldsm1", ofEpochMilli(1446216571000L), "", "", Some(""" [3,6,9] """), None)
//  )

}

trait SpecSupport extends FlatSpec with BeforeAndAfterEach {
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

  override def beforeEach: Unit =
    createSchema()

  override def afterEach: Unit = {}
}
