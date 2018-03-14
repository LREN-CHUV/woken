/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.api

import java.time.OffsetDateTime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.routing.FromConfig
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import ch.chuv.lren.woken.core.model.Shapes
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.core.{CoordinatorActor, CoordinatorConfig, ExperimentActor}
import ch.chuv.lren.woken.messages.datasets.{DatasetsQuery, DatasetsResponse}
import ch.chuv.lren.woken.service.{DatasetService, DispatcherService}

import scala.concurrent.ExecutionContext
import ch.chuv.lren.woken.api.MasterRouter.QueuesSize
import ch.chuv.lren.woken.backends.DockerJob
import ch.chuv.lren.woken.core.model.ErrorJobResult
import ch.chuv.lren.woken.service.{AlgorithmLibraryService, VariablesMetaService}
import MiningQueries._
import ch.chuv.lren.woken.config.{AlgorithmDefinition, AppConfiguration}
import ch.chuv.lren.woken.core.commands.JobCommands.{StartCoordinatorJob, StartExperimentJob}
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.variables._

object MasterRouter {

  // Incoming messages
  case object RequestQueuesSize

  // Responses
  case class QueuesSize(experiments: Int, mining: Int) {
    def isEmpty: Boolean = experiments == 0 && mining == 0
  }

  def props(appConfiguration: AppConfiguration,
            coordinatorConfig: CoordinatorConfig,
            datasetService: DatasetService,
            variablesMetaService: VariablesMetaService,
            dispatcherService: DispatcherService,
            algorithmLibraryService: AlgorithmLibraryService,
            algorithmLookup: String => Validation[AlgorithmDefinition]): Props =
    Props(
      new MasterRouter(
        appConfiguration,
        coordinatorConfig,
        dispatcherService,
        algorithmLibraryService,
        algorithmLookup,
        datasetService,
        variablesMetaService,
        experimentQuery2Job(variablesMetaService, coordinatorConfig.jobsConf),
        miningQuery2Job(variablesMetaService, coordinatorConfig.jobsConf, algorithmLookup)
      )
    )

}

case class MasterRouter(appConfiguration: AppConfiguration,
                        coordinatorConfig: CoordinatorConfig,
                        dispatcherService: DispatcherService,
                        algorithmLibraryService: AlgorithmLibraryService,
                        algorithmLookup: String => Validation[AlgorithmDefinition],
                        datasetService: DatasetService,
                        variablesMetaService: VariablesMetaService,
                        experimentQuery2JobF: ExperimentQuery => Validation[ExperimentActor.Job],
                        miningQuery2JobF: MiningQuery => Validation[DockerJob])
    extends Actor
    with ActorLogging {

  import MasterRouter.RequestQueuesSize

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContext            = context.dispatcher

  lazy val validationWorker: ActorRef = initValidationWorker
  lazy val scoringWorker: ActorRef    = initScoringWorker

  if (!appConfiguration.disableWorkers) {
    // Initialise the workers to test that they work and fail early otherwise
    val _ = (validationWorker, scoringWorker)
  }

  val experimentActiveActorsLimit: Int = appConfiguration.masterRouterConfig.miningActorsLimit
  val miningActiveActorsLimit: Int     = appConfiguration.masterRouterConfig.experimentActorsLimit

  var experimentJobsInFlight: Map[ExperimentActor.Job, (ActorRef, ActorRef)] = Map()
  var miningJobsInFlight: Map[DockerJob, (ActorRef, ActorRef)]               = Map()

  def receive: PartialFunction[Any, Unit] = {

    case MethodsQuery =>
      sender ! MethodsResponse(algorithmLibraryService.algorithms().compactPrint)

    case DatasetsQuery =>
      sender ! DatasetsResponse(datasetService.datasets())

    case varsQuery: VariablesForDatasetsQuery =>
      val initiator = sender()
      log.debug("querying variables...")

      Source.single(varsQuery.copy(datasets = datasetService.datasets().map(_.dataset)))
          .via(dispatcherService.dispatchVariablesQueryFlow(datasetService, variablesMetaService))
          .fold(Set[VariableMetaData]()) {
            println("folding")
            _ ++ _.variables
          }
          .map { varsMetaData => {
              log.debug(s"vars metadata $varsMetaData")
              initiator ! VariablesForDatasetsResponse(varsMetaData)
              VariablesForDatasetsResponse(varsMetaData)
            }
          }
          .runWith(Sink.last)

    //case MiningQuery(variables, covariables, groups, _, AlgorithmSpec(c, p))
    //    if c == "" || c == "data" =>
    // TODO To be implemented

    case query: MiningQuery =>
      val initiator = sender()
      if (miningJobsInFlight.size <= miningActiveActorsLimit) {
        val jobValidated = miningQuery2JobF(query)

        jobValidated.fold(
          errorMsg => {
            val error =
              ErrorJobResult("",
                             coordinatorConfig.jobsConf.node,
                             OffsetDateTime.now(),
                             query.algorithm.code,
                             errorMsg.reduceLeft(_ + ", " + _))
            initiator ! error.asQueryResult
          },
          job => runMining(query, initiator, job)
        )
      } else {
        val error =
          ErrorJobResult("", "", OffsetDateTime.now(), "experiment", "Too busy to accept new jobs.")
        initiator ! error.asQueryResult
      }

    case CoordinatorActor.Response(job, List(errorJob: ErrorJobResult)) =>
      log.warning(s"Received error while mining ${job.query}: $errorJob")
      miningJobsInFlight.get(job).foreach(im => im._1 ! errorJob.asQueryResult)
      miningJobsInFlight -= job

    case CoordinatorActor.Response(job, results) =>
      // TODO: we can only handle one result from the Coordinator handling a mining query.
      // Containerised algorithms that can produce more than one result (e.g. PFA model + images) are ignored
      log.info(s"Received results for mining ${job.query}: $results")
      val jobResult = results.head
      miningJobsInFlight.get(job).foreach(im => im._1 ! jobResult.asQueryResult)
      miningJobsInFlight -= job

    case query: ExperimentQuery =>
      val initiator = sender()
      log.debug(s"Received message: $query")
      if (experimentJobsInFlight.size <= experimentActiveActorsLimit) {
        runExperiment(query, initiator)
      } else {
        val error =
          ErrorJobResult("", "", OffsetDateTime.now(), "experiment", "Too busy to accept new jobs.")
        sender() ! error.asQueryResult
      }

    case ExperimentActor.Response(job, Left(results)) =>
      log.info(s"Received experiment error response $results")
      experimentJobsInFlight.get(job).foreach(im => im._1 ! results.asQueryResult)
      experimentJobsInFlight -= job

    case ExperimentActor.Response(job, Right(results)) =>
      log.info(s"Received experiment response $results")
      experimentJobsInFlight.get(job).foreach(im => im._1 ! results.asQueryResult)
      experimentJobsInFlight -= job

    case RequestQueuesSize =>
      sender() ! QueuesSize(mining = miningJobsInFlight.size,
                            experiments = experimentJobsInFlight.size)

    case Terminated(a) =>
      log.debug(s"Actor terminated: $a")
      miningJobsInFlight = miningJobsInFlight.filterNot(kv => kv._2._2 == a)
      experimentJobsInFlight = experimentJobsInFlight.filterNot(kv => kv._2._2 == a)
      if (miningJobsInFlight.nonEmpty)
        log.info(s"Mining active: ${miningJobsInFlight.size}")
      if (experimentJobsInFlight.nonEmpty)
        log.info(s"Experiments active: ${experimentJobsInFlight.size}")

    case e =>
      log.warning(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def runMining(query: MiningQuery, initiator: ActorRef, job: DockerJob): Unit =
    dispatcherService.dispatchTo(query.datasets) match {
      case (_, true) => startMiningJob(job, initiator)
      case _ =>
        log.info("Dispatch mining query to remote workers...")

        Source
          .single(query)
          .via(dispatcherService.remoteDispatchMiningFlow())
          .fold(List[QueryResult]()) {
            _ :+ _._2
          }
          .map {
            case List() =>
              ErrorJobResult("",
                             coordinatorConfig.jobsConf.node,
                             OffsetDateTime.now(),
                             query.algorithm.code,
                             "No results").asQueryResult

            case List(result) => result

            case listOfResults =>
              compoundResult(listOfResults)
          }
          .map { queryResult =>
            initiator ! queryResult
            queryResult
          }
          .runWith(Sink.last)
          .failed
          .foreach { e =>
            log.error(e, s"Cannot complete mining query $query")
            val error =
              ErrorJobResult("", "", OffsetDateTime.now(), "experiment", e.toString)
            initiator ! error.asQueryResult
          }
    }

  private def runExperiment(query: ExperimentQuery, initiator: ActorRef): Unit = {
    val jobValidated = experimentQuery2JobF(query)
    jobValidated.fold(
      errorMsg => {
        val error =
          ErrorJobResult("",
                         "",
                         OffsetDateTime.now(),
                         "experiment",
                         errorMsg.reduceLeft(_ + ", " + _))
        initiator ! error.asQueryResult
      },
      job =>
        dispatcherService.dispatchTo(query.trainingDatasets) match {
          case (_, true) => startExperimentJob(job, initiator)
          case _ =>
            log.info("Dispatch experiment query to remote workers...")

            Source
              .single(query)
              .via(dispatcherService.remoteDispatchExperimentFlow())
              .fold(List[QueryResult]()) {
                _ :+ _._2
              }
              .map {
                case List() =>
                  ErrorJobResult("",
                                 coordinatorConfig.jobsConf.node,
                                 OffsetDateTime.now(),
                                 "experiment",
                                 "No results").asQueryResult

                case List(result) => result

                case listOfResults =>
                  compoundResult(listOfResults)
              }
              .map { queryResult =>
                initiator ! queryResult
                queryResult
              }
              .runWith(Sink.last)
              .failed
              .foreach { e =>
                log.error(e, s"Cannot complete experiment query $query")
                val error =
                  ErrorJobResult("", "", OffsetDateTime.now(), "experiment", e.toString)
                initiator ! error.asQueryResult
              }
      }
    )
  }

  private def startMiningJob(job: DockerJob, initiator: ActorRef): Unit = {
    val miningActorRef = newCoordinatorActor
    miningActorRef ! StartCoordinatorJob(job)
    miningJobsInFlight += job -> (initiator -> miningActorRef)
  }

  private def startExperimentJob(job: ExperimentActor.Job, initiator: ActorRef): Unit = {
    val experimentActorRef = newExperimentActor
    experimentActorRef ! StartExperimentJob(job)
    experimentJobsInFlight += job -> (initiator -> experimentActorRef)
  }

  private[api] def newExperimentActor: ActorRef = {
    val ref = context.actorOf(ExperimentActor.props(coordinatorConfig, algorithmLookup))
    context watch ref
    ref
  }

  private[api] def newCoordinatorActor: ActorRef = {
    val ref = context.actorOf(CoordinatorActor.props(coordinatorConfig))
    context watch ref
    ref
  }

  private[api] def initValidationWorker: ActorRef =
    context.actorOf(FromConfig.props(Props.empty), "validationWorker")

  private[api] def initScoringWorker: ActorRef =
    context.actorOf(FromConfig.props(Props.empty), "scoringWorker")

  private def compoundResult(queryResults: List[QueryResult]): QueryResult = {
    import spray.json._
    import queryProtocol._

    QueryResult(
      jobId = "",
      node = coordinatorConfig.jobsConf.node,
      timestamp = OffsetDateTime.now(),
      shape = Shapes.compound.mime,
      algorithm = "compound",
      data = Some(queryResults.toJson),
      error = None
    )
  }

}
