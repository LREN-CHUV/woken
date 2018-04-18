package ch.chuv.lren.woken.dispatch

import java.time.OffsetDateTime

import akka.actor.Actor
import ch.chuv.lren.woken.core.CoordinatorConfig
import ch.chuv.lren.woken.core.model.Shapes
import ch.chuv.lren.woken.messages.query.QueryResult

trait QueriesActor extends Actor {
  def coordinatorConfig: CoordinatorConfig

  private[core] def compoundResult(queryResults: List[QueryResult]): QueryResult = {
    import spray.json._

    QueryResult(
      jobId = "",
      node = coordinatorConfig.jobsConf.node,
      timestamp = OffsetDateTime.now(),
      shape = Shapes.compound.mime,
      algorithm = "",
      data = Some(queryResults.toJson),
      error = None
    )
  }

}
