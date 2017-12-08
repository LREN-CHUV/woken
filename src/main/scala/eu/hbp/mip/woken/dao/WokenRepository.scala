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

package eu.hbp.mip.woken.dao

import cats._
import cats.implicits._
import eu.hbp.mip.woken.core.model.JobResult

import scala.collection.concurrent.TrieMap
import scala.language.higherKinds

/**
  * The interface to Woken database
  */
trait WokenRepository[F[_]] extends DAL {

  def jobResults: JobResultRepository[F]

}

/**
  * Algebra for persistance of JobResult
  */
trait JobResultRepository[F[_]] {

  def put(result: JobResult): F[JobResult]

  def get(jobId: String): F[Option[JobResult]]

}

class WokenInMemoryRepository[F[_]: Applicative] extends WokenRepository[F] {

  override val jobResults: JobResultRepository[F] = new JobResultRepository[F] {

    private val cache = new TrieMap[String, JobResult]

    override def put(result: JobResult): F[JobResult] = {
      cache.put(result.jobId, result)
      result.pure[F]
    }

    override def get(jobId: String): F[Option[JobResult]] =
      cache.get(jobId).pure[F]

  }

}
