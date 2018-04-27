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

import akka.http.scaladsl.model.Uri
import com.typesafe.config.Config
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.fp.Traverse
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
      val tables: Validation[List[String]] = f.validateStringList("tables")
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
