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

package eu.hbp.mip.woken.api

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import akka.routing.{ ActorRefRoutee, RoundRobinRoutingLogic, Router }
import spray.json._
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.core.{ CoordinatorActor, CoordinatorConfig, CoreActors, ExperimentActor }
import eu.hbp.mip.woken.core.model.JobResult
import FunctionsInOut._
import com.github.levkhomich.akka.tracing.ActorTracing
import com.typesafe.config.ConfigFactory
import eu.hbp.mip.woken.config.{ JdbcConfiguration, JobsConfiguration, WokenConfig }
import eu.hbp.mip.woken.dao.JobResultsDAL

object MasterRouter {

  def props(api: Api, chronosService: ActorRef, resultDatabase: JobResultsDAL): Props =
    Props(classOf[MasterRouter], api, chronosService, resultDatabase)

}

class MasterRouter(val api: Api, val chronosService: ActorRef, val resultDatabase: JobResultsDAL)
    extends Actor
    with ActorTracing
    with ActorLogging {

  // For the moment we only support one JobResult
  def createQueryResult(results: scala.collection.Seq[JobResult]): Any =
    if (results.length == 1) (QueryResult.apply _).tupled(JobResult.unapply(results.head).get)
    else QueryError("Cannot make sense of the query output")

  private val config = ConfigFactory.load()
  private val jobsConf = JobsConfiguration
    .read(config)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))
  private val coordinatorConfig = CoordinatorConfig(chronosService,
                                                    resultDatabase,
                                                    createQueryResult,
                                                    WokenConfig.app.dockerBridgeNetwork,
                                                    jobsConf,
                                                    JdbcConfiguration.factory(config))

  var miningRouter: Router = {
    val routees = Vector.fill(5) {
      val r = api.mining_service.newCoordinatorActor(coordinatorConfig)
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  var experimentRouter: Router = {
    val routees = Vector.fill(5) {
      val r = api.mining_service.newExperimentActor(coordinatorConfig)
      context watch r
      ActorRefRoutee(r)
    }
    Router(RoundRobinRoutingLogic(), routees)
  }

  def receive: PartialFunction[Any, Unit] = {
    case query: MethodsQuery =>
      sender ! Methods(MiningService.methods_mock.parseJson.compactPrint)

    case MiningQuery(variables, covariables, groups, _, Algorithm(c, n, p))
        if c == "" || c == "data" =>
    // TODO To be implemented

    case query: MiningQuery =>
      miningRouter.route(CoordinatorActor.Start(query2job(query)), sender())

    case query: ExperimentQuery =>
      experimentRouter.route(ExperimentActor.Start(query2job(query)), sender())

    case Terminated(a) =>
      log.info(s"Actor terminated: $a")

      if (miningRouter.routees.contains(ActorRefRoutee(a))) {
        miningRouter = miningRouter.removeRoutee(a)
        val r = api.mining_service.newCoordinatorActor(coordinatorConfig)
        context watch r
        miningRouter = miningRouter.addRoutee(r)
      } else {
        experimentRouter = experimentRouter.removeRoutee(a)
        val r = api.mining_service.newExperimentActor(coordinatorConfig)
        context watch r
        experimentRouter = experimentRouter.addRoutee(r)
      }

    case _ => // ignore
  }
}
