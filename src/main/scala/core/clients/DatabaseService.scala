package core.clients

import akka.actor.{Props, Actor}
import config.DatabaseConfig._
import core.model.JobResult
import dao.JobResultDao

import scala.concurrent.ExecutionContext

object DatabaseService {
  trait DatabaseWork
  trait DatabaseResult

  // Requests
  case class GetJobResults(requestId: String) extends DatabaseWork

  // Results
  case class JobResults(results: Seq[JobResult]) extends DatabaseResult

  def props(jobResultDao: JobResultDao): Props = Props(classOf[DatabaseService], jobResultDao)
}

class DatabaseService(val jobResultDao: JobResultDao) extends Actor {
  import DatabaseService._

  def receive = {

    case GetJobResults(requestId) => {
      import akka.pattern.pipe
      implicit val executionContext: ExecutionContext = context.dispatcher

      val originalSender = sender()
      val results = db.run {
        for {
          results <- jobResultDao.get(requestId)
        } yield results
      }

      results.map(JobResults) pipeTo originalSender
    }

  }
}
