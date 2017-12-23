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

import akka.NotUsed
import akka.actor.{ActorRef, ActorRefFactory, ActorSystem, PoisonPill}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import eu.hbp.mip.woken.WokenWSProtocol
import eu.hbp.mip.woken.api.MiningQueries.experimentQuery2job
import eu.hbp.mip.woken.api.swagger.MiningServiceApi
import eu.hbp.mip.woken.authentication.BasicAuthentication
import eu.hbp.mip.woken.config.{AlgorithmDefinition, AppConfiguration, JobsConfiguration}
import eu.hbp.mip.woken.core.{CoordinatorConfig, ExperimentActor}
import eu.hbp.mip.woken.core.commands.JobCommands.StartExperimentJob
import eu.hbp.mip.woken.core.model.JobResult
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import eu.hbp.mip.woken.dao.FeaturesDAL
import eu.hbp.mip.woken.json.DefaultJsonFormats
import eu.hbp.mip.woken.service.{AlgorithmLibraryService, JobResultService, VariablesMetaService}
import eu.hbp.mip.woken.json.ApiJsonSupport._
import eu.hbp.mip.woken.messages.external.{ExperimentQuery, MiningQuery}

import scala.concurrent.ExecutionContextExecutor

object MiningServiceWS

class MiningServiceWS(
                       val chronosService: ActorRef,
                       val featuresDatabase: FeaturesDAL,
                       val jobResultService: JobResultService,
                       val variablesMetaService: VariablesMetaService,
                       override val appConfiguration: AppConfiguration,
                       val coordinatorConfig: CoordinatorConfig,
                       val jobsConf: JobsConfiguration,
                       val algorithmLookup: String => Validation[AlgorithmDefinition]
                     )(implicit system: ActorSystem)
  extends MiningServiceApi
    with FailureHandling
    with PerRequestCreator
    with DefaultJsonFormats
    with BasicAuthentication {

  override def context: ActorRefFactory = system

  implicit val executionContext: ExecutionContextExecutor = context.dispatcher

  implicit val materializer: Materializer = ActorMaterializer()

  val routes: Route = mining ~ experiment ~ listMethods

  override def mining: Route = path("mining" / "job") {
    handleWebSocketMessages(miningProc)
  }

  override def experiment: Route = path("mining" / "experiment") {
    handleWebSocketMessages(experimentProc)
  }

  override def listMethods: Route = path("mining" / "list-methods") {
    handleWebSocketMessages(listMethodsProc)
  }

  private def listMethodsProc: Flow[Message, Message, Any] = Flow[Message].mapConcat {
    case tm: TextMessage =>
      if (tm.getStrictText == WokenWSProtocol.ListMethods.toString)
        TextMessage(Source.single(AlgorithmLibraryService().algorithms().compactPrint)) :: Nil
      else {
        Nil
      }
  }

  private def experimentProc: Flow[Message, Message, Any] = Flow[Message].mapConcat {

    val experimentActorJob: ActorRef = ??? //experimentJob(coordinatorConfig, algorithmLookup)

    val incomingMessages: Sink[Message, NotUsed] = Flow[Message].map {
      case TextMessage.Strict(jsonEncodedString) =>
        import spray.json._
        val query: ExperimentQuery = experimentQueryJsonFormat.read(jsonEncodedString.parseJson)
        experimentQuery2job(variablesMetaService, jobsConf)(query)
    }.to(Sink.actorRef[Validation[ExperimentActor.Job]](experimentActorJob, PoisonPill))

    val outgoingMessages: Source[Message, NotUsed] = Source.actorRef[JobResultService](10, OverflowStrategy.fail).mapMaterializedValue { out =>
      experimentActorJob ! StartExperimentJob(???)
      NoUsed
    }.map(jobResultService => jobResultService)

    //      job.fold(
    //        errors => complete(StatusCodes.BadRequest -> errors.toList.mkString(", ")),
    //        experimentActorJob =>
    //
    //      )
    ???
  }

  private def miningProc: Flow[Message, Message, Any] = Flow[Message].mapConcat {
    case tm: TextMessage =>
      import spray.json._
      val jsonEncodeStringMsg = tm.getStrictText
      val query: MiningQuery = SimpleQueryJsonFormat.read(jsonEncodeStringMsg.parseJson)
      ???
    case bm: BinaryMessage =>
      bm.dataStream.runWith(Sink.ignore)
      Nil
  }
}
