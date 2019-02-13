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

package ch.chuv.lren.woken.config
import cats.scalatest.{ ValidatedMatchers, ValidatedValues }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
import ch.chuv.lren.woken.core.model.database.TableColumn
import ch.chuv.lren.woken.messages.variables.SqlType
import ConfigurationInstances._

class DatabaseConfigurationTest
    extends WordSpec
    with Matchers
    with ValidatedMatchers
    with ValidatedValues {

  val dbConfigs: DatabaseId => Validation[DatabaseConfiguration] =
    DatabaseConfiguration.factory(ConfigFactory.load("test.conf"))

  "Configuration for databases" should {

    "read configuration for the woken database" in {
      val config = dbConfigs(wokenDb)

      config shouldBe valid
      config.value.id.code shouldBe "woken"
      config.value.user shouldBe "woken"
      config.value.host shouldBe "db"
      config.value.port shouldBe 5432
      config.value.database shouldBe "woken"
      config.value.poolSize shouldBe 1
      config.value.tables shouldBe empty
    }

    "read configuration for the features database and check the list of tables" in {
      val config = dbConfigs(featuresDb)

      config shouldBe valid
      config.value.id.code shouldBe "features_db"
      config.value.user shouldBe "features"
      config.value.host shouldBe "db"
      config.value.port shouldBe 5432
      config.value.database shouldBe "features"
      config.value.poolSize shouldBe 1
      config.value.tables.size shouldBe 6

      val cdeFeaturesATable = config.value.tables.get(cdeFeaturesATableId)
      cdeFeaturesATable.isDefined shouldBe true
      cdeFeaturesATable.get.table shouldBe cdeFeaturesATableId
      cdeFeaturesATable.get.owner shouldBe None
      cdeFeaturesATable.get.validateSchema shouldBe true
      cdeFeaturesATable.get.primaryKey shouldBe List(TableColumn("subjectcode", SqlType.varchar))
      cdeFeaturesATable.get.datasetColumn shouldBe Some(TableColumn("dataset", SqlType.varchar))

      val sampleDataTable = config.value.tables.get(sampleDataTableId)
      sampleDataTable.isDefined shouldBe true
      sampleDataTable.get.table shouldBe sampleDataTableId
      sampleDataTable.get.owner shouldBe None
      sampleDataTable.get.validateSchema shouldBe true
      sampleDataTable.get.primaryKey shouldBe List(TableColumn("ID", SqlType.int))
      sampleDataTable.get.datasetColumn shouldBe None

    }

  }
}
