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

import java.time.OffsetDateTime

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }
import eu.hbp.mip.woken.messages.external._
import eu.hbp.mip.woken.core.{ CoordinatorActor, CoordinatorConfig, ExperimentActor }
import FunctionsInOut._
import com.github.levkhomich.akka.tracing.ActorTracing
import com.typesafe.config.ConfigFactory
import eu.hbp.mip.woken.api.MasterRouter.QueuesSize
import eu.hbp.mip.woken.backends.DockerJob
import eu.hbp.mip.woken.config.{ DatabaseConfiguration, JobsConfiguration, WokenConfig }
import eu.hbp.mip.woken.core.model.ErrorJobResult
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.service.{ AlgorithmLibraryService, JobResultService, VariablesMetaService }

object MasterRouter {

  // Incoming messages
  case object RequestQueuesSize

  // Responses
  case class QueuesSize(experiments: Int, mining: Int) {
    def isEmpty: Boolean = experiments == 0 && mining == 0
  }

  def props(api: Api,
            featuresDatabase: FeaturesDAL,
            jobResultService: JobResultService,
            variablesMetaService: VariablesMetaService,
            algorithmLibraryService: AlgorithmLibraryService): Props =
    Props(
      new MasterRouter(api,
                       featuresDatabase,
                       jobResultService,
                       algorithmLibraryService,
                       experimentQuery2job(variablesMetaService),
                       miningQuery2job(variablesMetaService))
    )

}

case class MasterRouter(api: Api,
                        featuresDatabase: FeaturesDAL,
                        jobResultService: JobResultService,
                        algorithmLibraryService: AlgorithmLibraryService,
                        query2jobF: ExperimentQuery => ExperimentActor.Job,
                        query2jobFM: MiningQuery => DockerJob)
    extends Actor
    with ActorTracing
    with ActorLogging {

  import MasterRouter.RequestQueuesSize

  private lazy val config = ConfigFactory.load()
  private lazy val jobsConf = JobsConfiguration
    .read(config)
    .getOrElse(throw new IllegalStateException("Invalid configuration"))
  private lazy val coordinatorConfig = CoordinatorConfig(
    api.chronosHttp,
    WokenConfig.app.dockerBridgeNetwork,
    featuresDatabase,
    jobResultService,
    jobsConf,
    DatabaseConfiguration.factory(config)
  )

  var experimentsActiveActors: Set[ActorRef] = Set.empty
  val experimentsActiveActorsLimit: Int      = WokenConfig.app.masterRouterConfig.miningActorsLimit

  var miningActiveActors: Set[ActorRef] = Set.empty
  val miningActiveActorsLimit: Int      = WokenConfig.app.masterRouterConfig.experimentActorsLimit

  def receive: PartialFunction[Any, Unit] = {

    // TODO: MethodsQuery should be case object
    case _: MethodsQuery =>
      sender ! Methods(algorithmLibraryService.algorithms().compactPrint)

    case MiningQuery(variables, covariables, groups, _, Algorithm(c, n, p))
        if c == "" || c == "data" =>
    // TODO To be implemented

    case query: MiningQuery =>
      if (miningActiveActors.size <= miningActiveActorsLimit) {
        val miningActorRef = context.actorOf(CoordinatorActor.props(coordinatorConfig))
        miningActorRef.tell(CoordinatorActor.Start(query2jobFM(query)), sender())
        context watch miningActorRef
        miningActiveActors += miningActorRef
      } else {
        sender() ! List(
          ErrorJobResult("",
                         "",
                         OffsetDateTime.now(),
                         query.algorithm.code,
                         "Too busy to accept new jobs.")
        )
      }

    case CoordinatorActor.Response(results) =>
      sender() ! results

    case query: ExperimentQuery =>
      log.debug(s"Received message: $query")
      if (experimentsActiveActors.size <= experimentsActiveActorsLimit) {
        val experimentActorRef = context.actorOf(ExperimentActor.props(coordinatorConfig))
        experimentActorRef.tell(ExperimentActor.Start(query2jobF(query)), sender())
        context watch experimentActorRef
        experimentsActiveActors += experimentActorRef
      } else {
        sender() ! List(
          ErrorJobResult("", "", OffsetDateTime.now(), "experiment", "Too busy to accept new jobs.")
        )
      }

    case RequestQueuesSize =>
      sender() ! QueuesSize(mining = miningActiveActors.size,
                            experiments = experimentsActiveActors.size)

    case Terminated(a) =>
      log.debug(s"Actor terminated: $a")
      miningActiveActors -= a
      experimentsActiveActors -= a
      log.debug(s"Experiments active: ${experimentsActiveActors.size}")

    case e =>
      log.warning(s"Received unhandled request $e of type ${e.getClass}")

  }
}
