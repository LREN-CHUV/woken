package eu.hbp.mip.woken.service

import cats.effect.{Effect, IO, Sync}
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.dao.JobResultRepository

import scala.language.higherKinds

// Ok, end of the world IO occurs early on

/**
  * The entry point to our domain, works with repositories and validations to implement behavior
  * @param repository where we get our data
  */
class JobResultService(repository: JobResultRepository[IO])(implicit E: Effect[IO]) {

  def put(result: JobResult): JobResult = repository.put(result).unsafeRunSync()

  def get(jobId: String): Option[JobResult] = repository.get(jobId).unsafeRunSync()

}

object JobResultService {
  def apply(repo: JobResultRepository[IO])(implicit E: Effect[IO]): JobResultService =
    new JobResultService(repo)
}
