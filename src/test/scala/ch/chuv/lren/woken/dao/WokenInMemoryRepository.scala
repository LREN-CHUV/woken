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

import java.util.concurrent.atomic.AtomicInteger

import cats.Id
import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.core.model.jobs.JobResult
import ch.chuv.lren.woken.messages.datasets.TableId
import ch.chuv.lren.woken.messages.query.{ Query, QueryResult }
import sup.HealthCheck

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

class WokenInMemoryRepository[F[_]: Effect] extends WokenRepository[F] {

  private val seq = new AtomicInteger()

  /**
    * Generate a new sequence number used when generating table names
    */
  override def nextTableSeqNumber(): F[Int] = seq.incrementAndGet().pure[F]

  override val jobResults: JobResultRepository[F] = new JobResultRepository[F] {

    private val cache = new TrieMap[String, JobResult]

    override def put(result: JobResult): F[JobResult] =
      Effect[F].delay {
        val _ = cache.put(result.jobIdM.getOrElse(""), result)
        result
      }

    override def get(jobId: String): F[Option[JobResult]] =
      Effect[F].delay(cache.get(jobId))

    override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])
  }

  override def resultsCache: ResultsCacheRepository[F] = new ResultsCacheInMemoryRepository[F]

  override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])
}

class ResultsCacheInMemoryRepository[F[_]: Effect] extends ResultsCacheRepository[F] {

  override def put(result: QueryResult, query: Query): F[Unit] = ().pure[F]
  override def get(
      node: String,
      table: TableId,
      tableContentsHash: Option[String],
      query: Query
  ): F[Option[QueryResult]] = Option.empty[QueryResult].pure[F]

  override def reset(): F[Unit] = ().pure[F]

  override def cleanUnusedCacheEntries(): F[Unit] = ().pure[F]

  override def cleanTooManyCacheEntries(maxEntries: Int): F[Unit] = ().pure[F]

  override def cleanCacheEntriesForOldContent(
      table: String,
      tableContentHash: String
  ): F[Unit] = ().pure[F]

  override def healthCheck: HealthCheck[F, Id] = HealthCheck.liftFBoolean(true.pure[F])
}
