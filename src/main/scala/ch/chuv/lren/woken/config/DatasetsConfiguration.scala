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
import cats.data.NonEmptyList
import com.typesafe.config.Config
import ch.chuv.lren.woken.cromwell.core.ConfigUtil._
import ch.chuv.lren.woken.messages.datasets.{ AnonymisationLevel, Dataset, DatasetId, TableId }
import ch.chuv.lren.woken.messages.remoting.{ BasicAuthentication, RemoteLocation }
import cats.data.Validated._
import cats.implicits._

object DatasetsConfiguration {

  // Seems useful as Scala enumeration and Cats mapN don't appear to work together well
  private[this] def createDataset(dataset: String,
                                  label: String,
                                  description: String,
                                  tables: List[TableId],
                                  anonymisationLevel: String,
                                  location: Option[RemoteLocation]): Dataset = Dataset(
    DatasetId(dataset),
    label,
    description,
    tables,
    AnonymisationLevel.withName(anonymisationLevel),
    location
  )

  def read(config: Config, path: List[String], database: DatabaseId): Validation[Dataset] = {

    val datasetConfig = config.validateConfig(path.mkString("."))

    datasetConfig.andThen { f =>
      val dataset: Validation[String] =
        path.lastOption.map(_.validNel[String]).getOrElse("Empty path".invalidNel[String])
      val label       = f.validateString("label")
      val description = f.validateString("description")
      val tables: Validation[List[TableId]] = f
        .validateStringList("tables")
        .map(l => l.map(t => TableId(database.code, t)))
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
            .orElse(Option.empty[BasicAuthentication].validNel[String])

          (url, credentials) mapN RemoteLocation
        }
        .map(_.some)
        .orElse(Option.empty[RemoteLocation].validNel[String])
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

  def factory(config: Config, defaultDatabase: DatabaseId): DatasetId => Validation[Dataset] =
    dataset => read(config, List("datasets", dataset.code), defaultDatabase)

  def datasetNames(config: Config): Validation[Set[DatasetId]] =
    config.validateConfig("datasets").map(_.keys.map(DatasetId))

  def datasets(config: Config, defaultDatabase: DatabaseId): Validation[Map[DatasetId, Dataset]] = {
    val datasetFactory = factory(config, defaultDatabase)
    datasetNames(config)
      .andThen { names: Set[DatasetId] =>
        val m: List[Validation[(DatasetId, Dataset)]] =
          names.toList
            .map { datasetId =>
              val datasetIdV: Validation[DatasetId] = datasetId.validNel[String]
              datasetIdV -> datasetFactory(datasetId)
            }
            .map(_.tupled)
        val t: Validation[List[(DatasetId, Dataset)]] = m.sequence[Validation, (DatasetId, Dataset)]
        t.map(_.toMap)
      }
      .ensure(NonEmptyList("No datasets are configured", Nil))(_.nonEmpty)
  }

}
