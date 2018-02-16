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

package eu.hbp.mip.woken.core

import java.time.OffsetDateTime
import java.util.UUID

import akka.NotUsed
import akka.actor.{ Actor, ActorContext, ActorLogging, Props }
import akka.stream._
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Merge, Partition, Sink, Source, Zip }
import ch.chuv.lren.woken.messages.variables.VariableMetaData
import eu.hbp.mip.woken.core.validation.ValidatedAlgorithmFlow

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

//import com.github.levkhomich.akka.tracing.ActorTracing
import eu.hbp.mip.woken.core.commands.JobCommands.StartExperimentJob
import eu.hbp.mip.woken.config.AlgorithmDefinition
import eu.hbp.mip.woken.core.model.{ ErrorJobResult, JobResult, PfaExperimentJobResult }
import eu.hbp.mip.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query.{
  AlgorithmSpec,
  ExperimentQuery,
  MiningQuery,
  ValidationSpec
}

/**
  * We use the companion object to hold all the messages that the ``ExperimentActor`` receives.
  */
object ExperimentActor {

  // Incoming messages
  case class Job(
      jobId: String,
      inputDb: String,
      inputTable: String,
      query: ExperimentQuery,
      metadata: List[VariableMetaData]
  )

  case object Done

  // Output messages: JobResult containing the experiment PFA

  case class Response(job: Job, result: Either[ErrorJobResult, PfaExperimentJobResult])

  def props(coordinatorConfig: CoordinatorConfig,
            algorithmLookup: String => Validation[AlgorithmDefinition]): Props =
    Props(new ExperimentActor(coordinatorConfig, algorithmLookup))

}

/**
  * The job of this Actor in our application core is to service a request to start a job and wait for the result of the calculation.
  *
  * This actor will have the responsibility of spawning one ValidationActor plus one LocalCoordinatorActor per algorithm and aggregate
  * the results before responding
  *
  */
class ExperimentActor(val coordinatorConfig: CoordinatorConfig,
                      algorithmLookup: String => Validation[AlgorithmDefinition])
    extends Actor
    with ActorLogging
    /*with ActorTracing*/ {

  import ExperimentActor._

  val decider: Supervision.Decider = { _ =>
    Supervision.Stop
  }

  implicit val materializer: ActorMaterializer = ActorMaterializer(
    ActorMaterializerSettings(context.system).withSupervisionStrategy(decider)
  )
  implicit val ec: ExecutionContext = context.dispatcher

  lazy val experimentFlow: Flow[Job, Map[AlgorithmSpec, JobResult], NotUsed] =
    ExperimentFlow(coordinatorConfig, algorithmLookup, context).flow

  @SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.NonUnitStatements"))
  override def receive: PartialFunction[Any, Unit] = {
    case StartExperimentJob(job) if job.query.algorithms.isEmpty =>
      val initiator = sender()
      val msg       = "Experiment contains no algorithms"
      val result = ErrorJobResult(job.jobId,
                                  coordinatorConfig.jobsConf.node,
                                  OffsetDateTime.now(),
                                  "experiment",
                                  msg)
      coordinatorConfig.jobResultService.put(result)
      initiator ! Response(job, Left(result))
      context stop self

    case StartExperimentJob(job) if job.query.algorithms.nonEmpty =>
      val initiator  = sender()
      val thisActor  = self
      val algorithms = job.query.algorithms

      log.info("Start new experiment job")
      log.info(s"List of algorithms: ${algorithms.mkString(",")}")

      val future = Source
        .single(job)
        .via(experimentFlow)
        .runWith(Sink.head)
        .map { results =>
          log.info("Experiment - build final response")
          log.info(s"Algorithms: $algorithms")
          log.info(s"Results: $results")

          assert(results.size == algorithms.size, "There should be as many results as algorithms")
          assert(results.keySet equals algorithms.toSet,
                 "Algorithms defined in the results should match the incoming list of algorithms")

          val pfa = PfaExperimentJobResult(experimentJobId = job.jobId,
                                           experimentNode = coordinatorConfig.jobsConf.node,
                                           results = results)

          Response(job, Right(pfa))
        }

      future
        .andThen {
          case Success(r) =>
            coordinatorConfig.jobResultService.put(r.result.fold(identity, identity))
            initiator ! r
          case Failure(f) =>
            log.error(f, s"Cannot complete experiment ${job.jobId}: ${f.getMessage}")
            val result = ErrorJobResult(job.jobId,
                                        coordinatorConfig.jobsConf.node,
                                        OffsetDateTime.now(),
                                        "experiment",
                                        f.toString)
            val response = Response(job, Left(result))
            coordinatorConfig.jobResultService.put(result)
            initiator ! response
        }
        .onComplete { _ =>
          log.info("Stopping...")
          context stop thisActor
        }

    case e =>
      log.error(s"Unhandled message: $e")
      context stop self
  }

}

case class ExperimentFlow(
    coordinatorConfig: CoordinatorConfig,
    algorithmLookup: String => Validation[AlgorithmDefinition],
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext) {

  private case class AlgorithmValidationMaybe(job: ExperimentActor.Job,
                                              algorithmSpec: AlgorithmSpec,
                                              algorithmDefinition: Validation[AlgorithmDefinition],
                                              validations: List[ValidationSpec])
  private case class AlgorithmValidation(job: ExperimentActor.Job,
                                         algorithmSpec: AlgorithmSpec,
                                         subJob: ValidatedAlgorithmFlow.Job)

  private val validatedAlgorithmFlow = ValidatedAlgorithmFlow(coordinatorConfig, context)

  @SuppressWarnings(Array("org.wartremover.warts.NonUnitStatements"))
  def flow: Flow[ExperimentActor.Job, Map[AlgorithmSpec, JobResult], NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val jobSplitter = builder.add(splitJob)
        val partition = builder.add(
          Partition[AlgorithmValidationMaybe](2,
                                              a =>
                                                if (a.algorithmDefinition.isValid) 0
                                                else 1)
        )
        val merge = builder.add(Merge[(AlgorithmSpec, JobResult)](2))
        val toMap =
          builder.add(Flow[(AlgorithmSpec, JobResult)].fold[Map[AlgorithmSpec, JobResult]](Map()) {
            (m, r) =>
              m + r
          })

        // connect the graph
        jobSplitter ~> partition.in
        partition.out(0) ~> prepareMiningQuery ~> algorithmWithValidation ~> merge
        partition.out(1) ~> failJob ~> merge
        merge ~> toMap

        FlowShape(jobSplitter.in, toMap.out)
      })
      .named("run-experiment")

  private def splitJob: Flow[ExperimentActor.Job, AlgorithmValidationMaybe, NotUsed] =
    Flow[ExperimentActor.Job]
      .map { job =>
        val algorithms  = job.query.algorithms
        val validations = job.query.validations

        algorithms.map(a => AlgorithmValidationMaybe(job, a, algorithmLookup(a.code), validations))
      }
      .mapConcat(identity)
      .named("split-job")

  @SuppressWarnings(Array("org.wartremover.warts.EitherProjectionPartial"))
  private def failJob: Flow[AlgorithmValidationMaybe, (AlgorithmSpec, JobResult), NotUsed] =
    Flow[AlgorithmValidationMaybe]
      .map { a =>
        val errorMessage = a.algorithmDefinition.toEither.left.get
        a.algorithmSpec -> ErrorJobResult(UUID.randomUUID().toString,
                                          coordinatorConfig.jobsConf.node,
                                          OffsetDateTime.now(),
                                          a.algorithmSpec.code,
                                          errorMessage.reduceLeft(_ + ", " + _))
      }
      .named("fail-job")

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def prepareMiningQuery: Flow[AlgorithmValidationMaybe, AlgorithmValidation, NotUsed] =
    Flow[AlgorithmValidationMaybe]
      .map { a =>
        val job           = a.job
        val jobId         = UUID.randomUUID().toString
        val query         = job.query
        val algorithmSpec = a.algorithmSpec
        val algorithmDefinition = a.algorithmDefinition.getOrElse(
          throw new IllegalStateException("Expected a valid definition")
        )
        val validations = if (algorithmDefinition.predictive) a.validations else Nil
        val miningQuery = MiningQuery(
          user = query.user,
          variables = query.variables,
          covariables = query.covariables,
          grouping = query.grouping,
          filters = query.filters,
          targetTable = Some(job.inputTable),
          datasets = query.trainingDatasets,
          algorithm = algorithmSpec,
          executionPlan = None
        )
        val subJob = ValidatedAlgorithmFlow.Job(jobId,
                                                job.inputDb,
                                                job.inputTable,
                                                miningQuery,
                                                job.metadata,
                                                validations,
                                                algorithmDefinition)
        AlgorithmValidation(job, algorithmSpec, subJob)
      }
      .named("prepare-mining-query")

  private def algorithmWithValidation
    : Flow[AlgorithmValidation, (AlgorithmSpec, JobResult), NotUsed] =
    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        val broadcast               = builder.add(Broadcast[AlgorithmValidation](2))
        val takeSpec                = Flow[AlgorithmValidation].map(_.algorithmSpec)
        val takeSubjob              = Flow[AlgorithmValidation].map(_.subJob)
        val runAlgorithmAndValidate = validatedAlgorithmFlow.runAlgorithmAndValidate(4)
        val takeModel               = Flow[ValidatedAlgorithmFlow.ResultResponse].map(_.model)
        val zip                     = builder.add(Zip[AlgorithmSpec, JobResult]())

        broadcast.out(0) ~> takeSpec ~> zip.in0
        broadcast.out(1) ~> takeSubjob ~> runAlgorithmAndValidate ~> takeModel ~> zip.in1

        FlowShape(broadcast.in, zip.out)
      })
      .named("algorithm-with-validation")

}
