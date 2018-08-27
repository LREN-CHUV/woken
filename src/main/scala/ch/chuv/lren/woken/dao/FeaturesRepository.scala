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

package ch.chuv.lren.woken.dao

import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.core.model.{FeaturesTableDescription, TableColumn}
import ch.chuv.lren.woken.messages.datasets.DatasetId
import spray.json.JsObject
import cats._
import cats.implicits._

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
  * The interface to Features database
  *
  * @tparam F The monad wrapping the connection to the database
  */
trait FeaturesRepository[F[_]] extends Repository {

  /**
    * @return the list of features tables available in the database
    */
  def tables: Set[FeaturesTableDescription]

  /**
    * Provides the interface to a features table
    *
    * @param table Name of the table
    * @return an option to the features table repository
    */
  def featuresTable(table: String): F[Option[FeaturesTableRepository[F]]]

}

/**
  * Provides access to a features table.
  *
  * @tparam F The monad wrapping the connection to the database
  */
trait FeaturesTableRepository[F[_]] extends Repository {

  import FeaturesTableRepository.Headers

  /**
    * Total number of rows in the table
    *
    * @return number of rows
    */
  def count: F[Int]

  /**
    * Number of rows belonging to the dataset.
    * @param dataset The dataset used to filter rows
    * @return the number of rows in the dataset, 0 if dataset is not associated with the table
    */
  def count(dataset: DatasetId): F[Int]

  /**
    * @return all headers of the table
    */
  def columns: Headers

  def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])]

}

object FeaturesTableRepository {

  type Headers = List[TableColumn]

}

class FeaturesInMemoryRepository[F[_]: Applicative](
    override val tables: Set[FeaturesTableDescription]
) extends FeaturesRepository[F] {

  private val cache = new TrieMap[String, FeaturesTableRepository[F]]()

  override def featuresTable(table: String): F[Option[FeaturesTableRepository[F]]] =
    Option(
      cache
        .getOrElse(table, {
          val ftr = new FeaturesTableInMemoryRepository[F]()
          val _   = cache.put(table, ftr)
          ftr
        })
    ).pure[F]

}

class FeaturesTableInMemoryRepository[F[_]: Applicative] extends FeaturesTableRepository[F] {

  import FeaturesTableRepository.Headers

  override def count: F[Int] = 0.pure[F]

  override def count(dataset: DatasetId): F[Int] = 0.pure[F]

  override def columns: Headers = Nil

  override def features(query: FeaturesQuery): F[(Headers, Stream[JsObject])] =
    (List[TableColumn](), List[JsObject]().toStream).pure[F]
}
