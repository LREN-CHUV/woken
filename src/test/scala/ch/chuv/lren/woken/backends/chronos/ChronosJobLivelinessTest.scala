/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.backends.chronos

import java.time.OffsetDateTime

import ch.chuv.lren.woken.util.JsonUtils
import org.scalatest._

class ChronosJobLivelinessTest extends FlatSpec with Matchers with JsonUtils {

  "A response from Chronos" should "be parsable" in {

    import ChronosJobLiveliness._
    import spray.json._
    import DefaultJsonProtocol._

    val response   = loadJson("/backends/chronos/chronos-job-response.json")
    val liveliness = response.convertTo[List[ChronosJobLiveliness]].head

    val expected = ChronosJobLiveliness(
      name = "java_rapidminer_knn_78be29d9_bc05_4db2_a2e0_c3de218960bc",
      successCount = 1,
      errorCount = 0,
      lastSuccess = Some(OffsetDateTime.parse("2017-11-29T23:13:01.724Z")),
      lastError = None,
      softError = false,
      errorsSinceLastSuccess = 0,
      disabled = true
    )

    liveliness shouldBe expected

  }
}
