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

import com.typesafe.config.Config
import eu.hbp.mip.woken.cromwell.core.ConfigUtil._
import cats.data.Validated._
import cats.implicits._
import eu.hbp.mip.woken.config.AnonymisationLevel.AnonymisationLevel
import eu.hbp.mip.woken.fp.Traverse
import eu.hbp.mip.woken.messages.external.DatasetId

object AnonymisationLevel extends Enumeration {
  type AnonymisationLevel = Value
  val Identifying, Depersonalised, Anonymised = Value
}

case class DatasetLocation(dataset: DatasetId,
                           description: String,
                           location: Option[String],
                           anonymisationLevel: AnonymisationLevel)

object DatasetLocation {

  def apply2(dataset: String,
             description: String,
             location: Option[String],
             anonymisationLevel: String): DatasetLocation = DatasetLocation(
    DatasetId(dataset),
    description,
    location,
    AnonymisationLevel.withName(anonymisationLevel)
  )
}

object DatasetsConfiguration {

  def read(config: Config, path: List[String]): Validation[DatasetLocation] = {

    val federationConfig = config.validateConfig(path.mkString("."))

    federationConfig.andThen { f =>
      val dataset     = path.lastOption.map(lift).getOrElse("Empty path".invalidNel[String])
      val description = f.validateString("description")
      val location    = f.validateOptionalString("location")
      val anonymisationLevel: Validation[String] = f
        .validateString("anonymisationLevel")
        .ensure(
          throw new IllegalArgumentException(
            "anonymisationLevel: valid values are " + AnonymisationLevel.values.mkString(",")
          )
        ) { s =>
          AnonymisationLevel.withName(s); true
        }

      (dataset, description, location, anonymisationLevel) mapN DatasetLocation.apply2
    }

  }

  def factory(config: Config): String => Validation[DatasetLocation] =
    dataset => read(config, List("datasets", dataset))

  def datasetNames(config: Config): Validation[Set[String]] =
    config.validateConfig("datasets").map(_.keys)

  def datasets(config: Config): Validation[Map[DatasetId, DatasetLocation]] = {
    val datasetFactory = factory(config)
    datasetNames(config).andThen { names: Set[String] =>
      val m: List[Validation[(DatasetId, DatasetLocation)]] =
        names.toList.map(n => lift(DatasetId(n)) -> datasetFactory(n)).map(_.tupled)
      val t: Validation[List[(DatasetId, DatasetLocation)]] = Traverse.sequence(m)
      t.map(_.toMap)
    }
  }

}
