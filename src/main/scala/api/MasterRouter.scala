package api

import akka.actor.{Actor, Props, Terminated}
import akka.routing.{ActorRefRoutee, RoundRobinRoutingLogic, Router}
import core.{CoordinatorActor, ExperimentActor, LocalCoordinatorActor}
import FunctionsInOut._
import core.model.JobResult
import eu.hbp.mip.messages.external.{Algorithm, ExperimentQuery, MiningQuery, QueryResult, QueryError}

class MasterRouter(api: Api) extends Actor {

  // For the moment we only support one JobResult
  def createQueryResult(results: scala.collection.Seq[JobResult]): Any = {
    if (results.length == 1) (QueryResult.apply _).tupled(JobResult.unapply(results.head).get) else QueryError("Cannot make sense of the query output")
  }
  val factory : core.JobResults.Factory = createQueryResult _

  var miningRouter = {
    val routees = Vector.fill(5) {
      val r = api.mining_service.newCoordinatorActor(factory)
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  var experimentRouter = {
    val routees = Vector.fill(5) {
      val r = api.mining_service.newExperimentActor(factory)
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  def receive = {
    case MiningQuery(variables, covariables, groups, _, Algorithm(c, n, p)) if c == "" || c == "data" =>
    // TODO To be implemented
    case query: MiningQuery =>
      miningRouter.route(CoordinatorActor.Start(query2job(query)), sender())
    case query: ExperimentQuery =>
      experimentRouter.route(ExperimentActor.Start(query2job(query)), sender())
    case Terminated(a) =>

        if (miningRouter.routees.contains(ActorRefRoutee(a))) {
          miningRouter = miningRouter.removeRoutee(a)
          val r = context.actorOf(Props[LocalCoordinatorActor])
          context watch r
          miningRouter = miningRouter.addRoutee(r)
        } else {
          experimentRouter = experimentRouter.removeRoutee(a)
          val r = context.actorOf(Props[ExperimentActor])
          context watch r
          experimentRouter = experimentRouter.addRoutee(r)
        }
    case _ => // ignore
  }
}
