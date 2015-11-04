package core.clients

import akka.actor.{Props, Actor}
import core.model.JobResult
import dao.DAL

import scala.concurrent.ExecutionContext
import slick.jdbc.JdbcBackend.Database

object DatabaseService {
  trait DatabaseWork
  trait DatabaseResult

  // Requests
  case class GetJobResults(requestId: String) extends DatabaseWork

  // Results
  case class JobResults(results: Seq[JobResult]) extends DatabaseResult

  def props(dal: DAL, db: Database): Props = Props(classOf[DatabaseService], dal)
}

class DatabaseService(val dal: DAL, db: Database) extends Actor {
  import DatabaseService._

  def receive = {

    case GetJobResults(requestId) => {
      import akka.pattern.pipe
      implicit val executionContext: ExecutionContext = context.dispatcher

      val originalSender = sender()
      val results = db.run {
        for {
          results <- dal.getJobResults(requestId)
        } yield results
      }

      results.map(JobResults) pipeTo originalSender
    }

  }
}
