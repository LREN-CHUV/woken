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

package ch.chuv.lren.woken.backends.faas.chronos

import java.time.OffsetDateTime

import akka.actor.FSM.{ Failure, Normal }
import akka.actor._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import cats.effect.{ Effect, ExitCase, IO }

import scala.concurrent.{ ExecutionContext, Future }
import ch.chuv.lren.woken.backends.HttpClient.checkHealth
import ch.chuv.lren.woken.backends.faas.AlgorithmExecutor.TaggedS
import ch.chuv.lren.woken.backends.faas.{ AlgorithmExecutor, AlgorithmResults }
import ch.chuv.lren.woken.config.{ DatabaseConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.core.model.jobs.{ DockerJob, ErrorJobResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.core.fp.{ fromFutureWithGuarantee, runNow }
import ch.chuv.lren.woken.dao.JobResultRepository
import com.typesafe.scalalogging.LazyLogging
import sup.{ HealthCheck, mods }

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.language.higherKinds

// TODO: replace with Akka streams, similar example can be found at https://softwaremill.com/replacing-akka-actors-with-akka-streams/

case class ChronosExecutor[F[_]: Effect](system: ActorSystem,
                                         chronosService: ActorRef,
                                         dockerBridgeNetwork: Option[String],
                                         jobResultService: JobResultRepository[F],
                                         jobsConf: JobsConfiguration,
                                         jdbcConfF: String => Validation[DatabaseConfiguration])
    extends AlgorithmExecutor[F]
    with LazyLogging {

  private val jobAsync = CoordinatorActor.executeJobAsync(
    CoordinatorConfig(chronosService, dockerBridgeNetwork, jobResultService, jobsConf, jdbcConfF),
    system
  )

  /**
    * Name of the current node (or cluster) where Docker containers are executed
    */
  override def node: String = jobsConf.node

  override def execute(job: DockerJob): F[AlgorithmResults] =
    fromFutureWithGuarantee[F, AlgorithmResults](jobAsync(job), {
      case ExitCase.Error(t) =>
        IO.delay(
          logger.error(s"Cannot complete Docker job ${job.toString}", t)
        )
      case _ => IO(())
    })

  override def healthCheck: HealthCheck[F, TaggedS] = {
    val chronosUrl = jobsConf.chronosServerUrl
    val url        = s"$chronosUrl/v1/scheduler/jobs"

    checkHealth(url)
      .through[F, TaggedS](mods.tagWith("Algorithm executor (Chronos)"))
  }

}

/**
  * Start mining command.
  *
  * @param job - docker job
  * @param replyTo Actor to reply to. Can be Actor.noSender when the ask pattern is used. This information is added in preparation for Akka Typed
  */
case class StartCoordinatorJob(job: DockerJob, replyTo: ActorRef)

case class CoordinatorConfig[F[_]](chronosService: ActorRef,
                                   dockerBridgeNetwork: Option[String],
                                   jobResultService: JobResultRepository[F],
                                   jobsConf: JobsConfiguration,
                                   jdbcConfF: String => Validation[DatabaseConfiguration])

/**
  * We use the companion object to hold all the messages that the ``CoordinatorActor``
  * receives.
  */
object CoordinatorActor {

  // Internal messages
  case object CheckDb
  case object CheckChronos

  // Responses

  // TODO: we can return only one JobResult at the moment

  def props[F[_]: Effect](coordinatorConfig: CoordinatorConfig[F]): Props =
    Props(
      new CoordinatorActor(coordinatorConfig)
    )

  def actorName(job: DockerJob): String =
    s"LocalCoordinatorActor_job_${job.jobId}_${job.jobName}"

  private[this] def future[F[_]: Effect](
      coordinatorConfig: CoordinatorConfig[F],
      system: ActorSystem
  )(job: DockerJob): Future[AlgorithmResults] = {
    val worker = system.actorOf(
      CoordinatorActor.props(coordinatorConfig),
      actorName(job)
    )

    implicit val askTimeout: Timeout = Timeout(1 day)

    (worker ? StartCoordinatorJob(job, Actor.noSender))
      .mapTo[AlgorithmResults]

  }

  type ExecuteJobAsync = DockerJob => Future[AlgorithmResults]

  def executeJobAsync[F[_]: Effect](coordinatorConfig: CoordinatorConfig[F],
                                    system: ActorSystem): ExecuteJobAsync =
    future(coordinatorConfig, system)

}

/** FSM States and internal data */
private[chronos] object CoordinatorStates {

  // FSM States

  sealed trait State

  case object WaitForNewJob extends State

  case object SubmittedJobToChronos extends State

  case object RequestFinalResult extends State

  /** Called when we know from Chronos that the job is complete yet results have not appeared yet in the database */
  case object ExpectFinalResult extends State

  // FSM state data

  trait StateData {
    def job: DockerJob
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  case object Uninitialized extends StateData {
    def job = throw new IllegalAccessException()
  }

  case class PartialLocalData(replyTo: ActorRef,
                              job: DockerJob,
                              chronosJob: ChronosJob,
                              pollDbCount: Int,
                              timeoutTime: Long)
      extends StateData

  case class ExpectedLocalData(replyTo: ActorRef,
                               job: DockerJob,
                               chronosJob: ChronosJob,
                               pollDbCount: Int,
                               timeoutTime: Long)
      extends StateData
}

// TODO: Chronos can call a callback url when a job is complete, use that functionality

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of making two requests and then aggregating them together:
  *   - One request to Chronos to start the job
  *   - Then a separate request in the database for the results, repeated until enough results are present
  *
  *  _________________           _______________________                   ______________________
  * |                 | Start   |                       | Even(Ok, data)   |                    |
  * | WaitForNewJob   | ------> | SubmittedJobToChronos |----------------> | RequestFinalResult | ==> results
  * | (Uninitialized) |         | (PartialLocalData)    |                  | (PartialLocalData) |
  *  -----------------           -----------------------                    --------------------
  *
  */
class CoordinatorActor[F[_]: Effect](coordinatorConfig: CoordinatorConfig[F])
    extends Actor
    with LazyLogging
    with FSM[CoordinatorStates.State, CoordinatorStates.StateData] {

  import CoordinatorActor._
  import CoordinatorStates._

  val repeatDuration: FiniteDuration = 200.milliseconds
  val startTime: Long                = System.currentTimeMillis

  startWith(WaitForNewJob, Uninitialized)
  logger.info("Local coordinator actor started...")

  when(WaitForNewJob) {
    case Event(StartCoordinatorJob(job, requestedReplyTo), Uninitialized) =>
      val replyTo = if (requestedReplyTo == Actor.noSender) sender() else requestedReplyTo

      import ChronosService._
      val chronosJob: Validation[ChronosJob] =
        JobToChronos(job,
                     coordinatorConfig.dockerBridgeNetwork,
                     coordinatorConfig.jobsConf,
                     coordinatorConfig.jdbcConfF)

      chronosJob.fold[State](
        { err =>
          val msg = err.toList.mkString
          logger.error(msg)
          replyTo ! errorResponse(job, msg)
          stop(Failure(msg))
        }, { cj =>
          coordinatorConfig.chronosService ! Schedule(cj, self)
          logger.info(
            s"Wait for Chronos to fulfill job ${job.jobId}"
          )
          goto(SubmittedJobToChronos) using PartialLocalData(
            replyTo = replyTo,
            job = job,
            chronosJob = cj,
            pollDbCount = 0,
            timeoutTime = System.currentTimeMillis + 1.day.toMillis
          )
        }
      )

  }

  // Process the response to the POST request sent to Chronos
  when(SubmittedJobToChronos) {

    case Event(ChronosService.Ok, data: PartialLocalData) =>
      logger.info(s"Job ${data.job} posted to Chronos")
      goto(RequestFinalResult) using data

    case Event(e: ChronosService.Error, data: PartialLocalData) =>
      val msg =
        s"Cannot complete job ${data.job.jobId} using ${data.job.algorithmDefinition.dockerImage}, received error: ${e.message}"
      logger.error(msg)
      data.replyTo ! errorResponse(data.job, msg)
      stop(Failure(msg))

    case Event(_: Timeout @unchecked, data: PartialLocalData) =>
      val msg =
        s"Cannot complete job ${data.job.jobId} using ${data.job.algorithmDefinition.dockerImage}, timeout while connecting to Chronos"
      logger.error(msg)
      data.replyTo ! errorResponse(data.job, msg)
      stop(Failure(msg))
  }

  // Track job status until its completion
  when(RequestFinalResult, stateTimeout = repeatDuration) {

    // Handle scheduled ticks
    case Event(StateTimeout, data: PartialLocalData) =>
      if (System.currentTimeMillis > data.timeoutTime) {
        val msg =
          s"Cannot complete job ${data.job.jobId} using ${data.job.algorithmDefinition.dockerImage}, job timed out"
        logger.error(msg)
        data.replyTo ! errorResponse(data.job, msg)
        stop(Failure(msg))
      } else {
        if (data.pollDbCount % 10 == 0) {
          self ! CheckChronos
        } else {
          self ! CheckDb
        }
        stay() forMax repeatDuration
      }

    // Check the database for the job result; prepare the next tick or send back the response if the job completed
    case Event(CheckDb, data: PartialLocalData) =>
      val results = runNow(coordinatorConfig.jobResultService.get(data.job.jobId))
      if (results.nonEmpty) {
        logger.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! AlgorithmResults(data.job, results.toList)
        logger.info("Stopping...")
        stop(Normal)
      } else {
        val updatedData = data.copy(pollDbCount = data.pollDbCount + 1)
        if (updatedData.pollDbCount % 10 == 5)
          logger.info(s"Polling job results database for job ${data.job.jobId}...")
        stay() using updatedData forMax repeatDuration
      }

    // Check Chronos for the job status; prepare the next tick
    case Event(CheckChronos, data: PartialLocalData) =>
      coordinatorConfig.chronosService ! ChronosService.Check(data.job.jobId, data.chronosJob, self)
      stay() forMax repeatDuration

    // Handle Chronos responses
    case Event(ChronosService.JobComplete(jobId, success), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        logger.warn(
          s"Chronos returned job complete for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      val results = runNow(coordinatorConfig.jobResultService.get(data.job.jobId))
      if (results.nonEmpty) {
        logger.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! AlgorithmResults(data.job, results.toList)

        val reportedSuccess = !results.exists { case _: ErrorJobResult => true; case _ => false }
        if (reportedSuccess != success) {
          logger.warn(
            s"Chronos reported that job ${data.job.jobId} using Docker image ${data.job.algorithmDefinition.dockerImage} is ${if (!success)
              "not "}successful, however the job results ${if (reportedSuccess) "do not "}contain an error"
          )
        }
        logger.info("Stopping...")
        stop(Normal)
      } else {
        // Use a short timeout here as Chronos reported completion of the job, we should just wait for results to
        // appear in the JobResult database. Otherwise, the algorithm was not well coded and did not return any result.
        goto(ExpectFinalResult) using ExpectedLocalData(
          replyTo = data.replyTo,
          job = data.job,
          chronosJob = data.chronosJob,
          pollDbCount = 0,
          timeoutTime = System.currentTimeMillis + 30.seconds.toMillis
        )
      }

    case Event(ChronosService.JobNotFound(jobId), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        logger.warn(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      val msg =
        s"Chronos lost track of job ${data.job.jobId} using ${data.job.algorithmDefinition.dockerImage}, it may have been stopped manually"
      logger.error(msg)
      data.replyTo ! errorResponse(data.job, msg)
      stop(Failure(msg))

    case Event(ChronosService.JobQueued(jobId), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        logger.warn(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      // Nothing more to do, wait
      stay() forMax repeatDuration

    case Event(ChronosService.JobUnknownStatus(jobId, status), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        logger.warn(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      logger.warn(
        s"Chronos reported status $status for job ${data.job.jobId} using ${data.job.algorithmDefinition.dockerImage}"
      )
      // Nothing more to do, wait
      stay() forMax repeatDuration

    case Event(ChronosService.ChronosUnresponsive(jobId, error), data: PartialLocalData) =>
      if (jobId != data.job.jobId) {
        logger.warn(
          s"Chronos returned job not found for job #$jobId, but was expecting job #{data.job.jobId}"
        )
      }
      logger.warn(
        s"Chronos appear unresponsive with error $error while checking job ${data.job.jobId} using ${data.job.algorithmDefinition.dockerImage}"
      )
      // TODO: if Chronos is down for too long, enter panic state!
      // Nothing more to do, wait
      stay() forMax repeatDuration

    case Event(f: Future[_], _) =>
      implicit val ec: ExecutionContext = context.dispatcher
      f.pipeTo(self)
      stay() forMax repeatDuration
  }

  when(ExpectFinalResult, stateTimeout = repeatDuration) {

    // Handle scheduled ticks
    case Event(StateTimeout, data: ExpectedLocalData) =>
      if (System.currentTimeMillis > data.timeoutTime) {
        val msg =
          s"Job ${data.job.jobId} using ${data.job.algorithmDefinition.dockerImage} has completed in Chronos, but encountered timeout while waiting for job results.\n" +
            "Does the algorithm store its results or errors in the output database?"
        logger.error(msg)
        data.replyTo ! errorResponse(data.job, msg)
        stop(Failure(msg))
      } else {
        self ! CheckDb
        stay() forMax repeatDuration
      }

    // Check the database for the job result; prepare the next tick or send back the response if the job completed
    case Event(CheckDb, data: ExpectedLocalData) =>
      val results = runNow(coordinatorConfig.jobResultService.get(data.job.jobId))
      if (results.nonEmpty) {
        logger.info(s"Received results for job ${data.job.jobId}")
        data.replyTo ! AlgorithmResults(data.job, results.toList)
        logger.info("Stopping...")
        stop(Normal)
      } else {
        stay() using data.copy(pollDbCount = data.pollDbCount + 1) forMax repeatDuration
      }

    case Event(f: Future[_], _) =>
      implicit val ec: ExecutionContext = context.dispatcher
      f.pipeTo(self)
      stay() forMax repeatDuration

  }

  whenUnhandled {
    case Event(e, s) =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass} in state $stateName/$s")
      stay forMax repeatDuration
  }

  def transitions: TransitionHandler = {
    case SubmittedJobToChronos -> RequestFinalResult =>
      self ! CheckDb
  }

  onTransition(transitions)

  onTermination {
    // TODO: all jobs should be cleaned from Chronos after completion, but we keep the success for now for reporting
    //case StopEvent(FSM.Normal, RequestFinalResult | ExpectFinalResult, data) => chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Shutdown, _, data: PartialLocalData) =>
      coordinatorConfig.chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Shutdown, _, data: ExpectedLocalData) =>
      coordinatorConfig.chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Failure(_), RequestFinalResult, data: PartialLocalData) =>
      coordinatorConfig.chronosService ! ChronosService.Cleanup(data.chronosJob)
    case StopEvent(FSM.Failure(_), ExpectFinalResult, data: ExpectedLocalData) =>
      coordinatorConfig.chronosService ! ChronosService.Cleanup(data.chronosJob)
  }

  initialize()

  private def errorResponse(job: DockerJob, msg: String) =
    AlgorithmResults(job,
                     List(
                       ErrorJobResult(Some(job.jobId),
                                      coordinatorConfig.jobsConf.node,
                                      OffsetDateTime.now(),
                                      Some(job.algorithmSpec.code),
                                      msg)
                     ))

}
