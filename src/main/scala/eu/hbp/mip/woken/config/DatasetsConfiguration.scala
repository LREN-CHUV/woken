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

import akka.http.scaladsl.model.Uri
import com.typesafe.config.Config
import eu.hbp.mip.woken.cromwell.core.ConfigUtil._
import eu.hbp.mip.woken.fp.Traverse
import ch.chuv.lren.woken.messages.datasets.{ AnonymisationLevel, Dataset, DatasetId }
import cats.data.Validated._
import cats.implicits._
import ch.chuv.lren.woken.messages.remoting.{ BasicAuthentication, RemoteLocation }

object DatasetsConfiguration {

  // Seems useful as Scala enumeration and Cats mapN don't appear to work together well
  def createDataset(dataset: String,
                    label: String,
                    description: String,
                    tables: List[String],
                    anonymisationLevel: String,
                    location: Option[RemoteLocation]): Dataset = Dataset(
    DatasetId(dataset),
    label,
    description,
    tables,
    AnonymisationLevel.withName(anonymisationLevel),
    location
  )

  def read(config: Config, path: List[String]): Validation[Dataset] = {

    val datasetConfig = config.validateConfig(path.mkString("."))

    datasetConfig.andThen { f =>
      val dataset                          = path.lastOption.map(lift).getOrElse("Empty path".invalidNel[String])
      val label                            = f.validateString("label")
      val description                      = f.validateString("description")
      val tables: Validation[List[String]] = f.validateStringList("tables").orElse(lift(List()))
      val location: Validation[Option[RemoteLocation]] = f
        .validateConfig("location")
        .andThen { cl =>
          val url: Validation[Uri] = cl.validateString("url").map(Uri.apply)
          val credentials: Validation[Option[BasicAuthentication]] = cl
            .validateConfig("basicAuth")
            .andThen { cc =>
              val user     = cc.validateString("user")
              val password = cc.validateString("password")

              (user, password) mapN BasicAuthentication
            }
            .map(_.some)
            .orElse(lift(None.asInstanceOf[Option[BasicAuthentication]]))

          (url, credentials) mapN RemoteLocation
        }
        .map(_.some)
        .orElse(lift(None.asInstanceOf[Option[RemoteLocation]]))
      val anonymisationLevel: Validation[String] = f
        .validateString("anonymisationLevel")
        .ensure(
          throw new IllegalArgumentException(
            "anonymisationLevel: valid values are " + AnonymisationLevel.values.mkString(",")
          )
        ) { s =>
          AnonymisationLevel.withName(s); true
        }

      (dataset, label, description, tables, anonymisationLevel, location) mapN createDataset
    }

  }

  def factory(config: Config): String => Validation[Dataset] =
    dataset => read(config, List("datasets", dataset))

  def datasetNames(config: Config): Validation[Set[String]] =
    config.validateConfig("datasets").map(_.keys)

  def datasets(config: Config): Validation[Map[DatasetId, Dataset]] = {
    val datasetFactory = factory(config)
    datasetNames(config).andThen { names: Set[String] =>
      val m: List[Validation[(DatasetId, Dataset)]] =
        names.toList.map(n => lift(DatasetId(n)) -> datasetFactory(n)).map(_.tupled)
      val t: Validation[List[(DatasetId, Dataset)]] = Traverse.sequence(m)
      t.map(_.toMap)
    }
  }

}
