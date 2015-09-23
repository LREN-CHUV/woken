package core.clients

import akka.actor.Actor
import config.DatabaseConfig._
import core.model.results.BoxPlotResult
import dao.BoxPlotResultDao

import scala.concurrent.ExecutionContext

object DatabaseService {

  case class GetBoxPlotResults(requestId: String)
  case class BoxPlotResults(data: Seq[BoxPlotResult])

}

class DatabaseService(val bpResultDao: BoxPlotResultDao) extends Actor {
  import DatabaseService._

  def receive = {

    case GetBoxPlotResults(requestId) => {
      import akka.pattern.pipe
      implicit val executionContext: ExecutionContext = context.dispatcher

      val originalSender = sender()
      val results = db.run {
        for {
          results <- bpResultDao.get(requestId)
        } yield results
      }

      results.map(BoxPlotResults) pipeTo originalSender
    }

  }
}
