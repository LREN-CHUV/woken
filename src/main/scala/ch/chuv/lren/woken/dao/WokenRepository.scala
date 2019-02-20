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

import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.core.model.jobs.JobResult
import ch.chuv.lren.woken.messages.datasets.TableId
import ch.chuv.lren.woken.messages.query.{ Query, QueryResult }

import scala.language.higherKinds

/**
  * The interface to Woken database.
  *
  * This database contains internal tables and functions
  */
trait WokenRepository[F[_]] extends Repository[F] {

  /**
    * Generate a new sequence number used when generating table names
    */
  def nextTableSeqNumber(): F[Int]

  def jobResults: JobResultRepository[F]

  def resultsCache: ResultsCacheRepository[F]

}

/**
  * Algebra for persistence of JobResult
  */
trait JobResultRepository[F[_]] extends Repository[F] {

  def put(result: JobResult): F[JobResult]

  def get(jobId: String): F[Option[JobResult]]

  // TODO: clear old job results
}

trait ResultsCacheRepository[F[_]] extends Repository[F] {

  def put(result: QueryResult, query: Query): F[Unit]

  def get(node: String,
          table: TableId,
          tableContentsHash: Option[String],
          query: Query): F[Option[QueryResult]]

  def reset(): F[Unit]

  def clean()(implicit effect: Effect[F]): F[Unit] =
    for {
      _ <- cleanUnusedCacheEntries()
      _ <- cleanTooManyCacheEntries(maxEntries = 10000)
      // TODO _ <- cleanCacheEntriesForOldContent()
    } yield ()

  def cleanUnusedCacheEntries(): F[Unit]
  def cleanTooManyCacheEntries(maxEntries: Int): F[Unit]
  def cleanCacheEntriesForOldContent(table: String, tableContentHash: String): F[Unit]

}

// TODO: keep track of the stats around job processing, it should be filled from old results being removed from job result table
