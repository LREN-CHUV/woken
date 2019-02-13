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
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.datasets.{ AnonymisationLevel, Dataset, DatasetId }
import org.scalatest.{ Matchers, WordSpec }
import AnonymisationLevel._
import akka.http.scaladsl.model.Uri
import ch.chuv.lren.woken.messages.remoting.{ BasicAuthentication, RemoteLocation }

class DatasetsConfigurationTest
    extends WordSpec
    with Matchers
    with ValidatedMatchers
    with ValidatedValues {

  val localDatasetConfigs: DatasetId => Validation[Dataset] =
    DatasetsConfiguration.factory(localNodeConfigSource, featuresDb)

  val remoteDatasetConfigs: DatasetId => Validation[Dataset] =
    DatasetsConfiguration.factory(centralNodeConfigSource, featuresDb)

  "Configuration for datasets" should {

    "ignore invalid datasets" in {
      localDatasetConfigs(DatasetId("invalid")) shouldBe invalid
    }

    "read configuration for sample dataset" in {
      val config = localDatasetConfigs(DatasetId("sample"))

      config shouldBe valid
      config.value.id.code shouldBe "sample"
      config.value.label shouldBe "Sample data"
      config.value.description shouldBe "Sample data"
      config.value.tables shouldBe List(sampleDataTableId)
      config.value.anonymisationLevel shouldBe Anonymised
      config.value.location shouldBe None
    }

    "read configuration for nida-synthdata dataset" in {
      val config = localDatasetConfigs(DatasetId("nida-synthdata"))

      config shouldBe valid
      config.value.id.code shouldBe "nida-synthdata"
      config.value.label shouldBe "NIDA"
      config.value.description shouldBe "Demo dataset NIDA"
      config.value.tables shouldBe List(cdeFeaturesBTableId, cdeFeaturesMixedTableId)
      config.value.anonymisationLevel shouldBe Depersonalised
      config.value.location shouldBe None
    }

    "read configuration for remote data2 dataset" in {
      val config = remoteDatasetConfigs(DatasetId("remoteData2"))

      config shouldBe valid
      config.value.id.code shouldBe "remoteData2"
      config.value.label shouldBe "Remote dataset #2"
      config.value.description shouldBe "Remote dataset #2"
      config.value.tables shouldBe List(cdeFeaturesATableId)
      config.value.anonymisationLevel shouldBe Depersonalised
      config.value.location shouldBe Some(
        RemoteLocation(Uri("http://service.remote/2"),
                       Some(BasicAuthentication("woken", "wokenpwd")))
      )
    }
  }
}
