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

import cats._
import cats.implicits._
import ch.chuv.lren.woken.core.model.JobResult

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
  * The interface to Woken database
  */
trait WokenRepository[F[_]] extends Repository {

  def jobResults: JobResultRepository[F]

}

/**
  * Algebra for persistence of JobResult
  */
trait JobResultRepository[F[_]] extends Repository {

  def put(result: JobResult): F[JobResult]

  def get(jobId: String): F[Option[JobResult]]

}

class WokenInMemoryRepository[F[_]: Applicative] extends WokenRepository[F] {

  override val jobResults: JobResultRepository[F] = new JobResultRepository[F] {

    private val cache = new TrieMap[String, JobResult]

    override def put(result: JobResult): F[JobResult] = {
      val _ = cache.put(result.jobIdM.getOrElse(""), result)
      result.pure[F]
    }

    override def get(jobId: String): F[Option[JobResult]] =
      cache.get(jobId).pure[F]

  }

}
