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

package eu.hbp.mip.woken.service

import cats.effect.{ Effect, IO }
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.dao.JobResultRepository

import scala.language.higherKinds

// Ok, end of the world IO occurs early on

/**
  * The entry point to our domain, works with repositories and validations to implement behavior
  * @param repository where we get our data
  */
class JobResultService(repository: JobResultRepository[IO])(implicit E: Effect[IO]) {

  def putIO(result: JobResult): IO[JobResult] = repository.put(result)

  def put(result: JobResult): JobResult = putIO(result).unsafeRunSync()

  def getIO(jobId: String): IO[Option[JobResult]] = repository.get(jobId)

  def get(jobId: String): Option[JobResult] = getIO(jobId).unsafeRunSync()
}

object JobResultService {
  def apply(repo: JobResultRepository[IO])(implicit E: Effect[IO]): JobResultService =
    new JobResultService(repo)
}
