package core.clients

import akka.actor.{Props, Actor}
import core.model.JobResult
import dao.DAL

object DatabaseService {
  trait DatabaseWork
  trait DatabaseResult

  // Requests
  case class GetJobResults(jobId: String) extends DatabaseWork

  // Results
  case class JobResults(results: Seq[JobResult]) extends DatabaseResult

  def props(dal: DAL): Props = Props(classOf[DatabaseService], dal)
}

class DatabaseService(val dal: DAL) extends Actor {
  import DatabaseService._

  def receive = {

    case GetJobResults(jobId) => {

      val originalSender = sender()
      val results = dal.findJobResults(jobId)

      originalSender ! results
    }

  }
}
