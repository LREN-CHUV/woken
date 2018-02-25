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

package ch.chuv.lren.woken.service

import cats.effect.{ Effect, IO }
import ch.chuv.lren.woken.core.model.JobResult
import ch.chuv.lren.woken.dao.JobResultRepository

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
