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

package ch.chuv.lren.woken.core.validation

import akka.NotUsed
import akka.actor.ActorContext
import akka.stream._
import akka.stream.scaladsl.{ Flow, Sink, Source }
import ch.chuv.lren.woken.config.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.{ ExperimentJobResult, PfaJobResult, ValidationJob }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.APIJsonProtocol
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.service.DispatcherService
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }

import APIJsonProtocol._

object RemoteValidationFlow {

  case class ValidationContext(query: ExperimentQuery, experimentResult: ExperimentJobResult)
  case class PartialValidation(query: ExperimentQuery,
                               experimentResult: ExperimentJobResult,
                               algorithmSpec: AlgorithmSpec,
                               model: Option[PfaJobResult])

}

case class RemoteValidationFlow(
    dispatcherService: DispatcherService,
    algorithmLookup: String => Validation[AlgorithmDefinition],
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import RemoteValidationFlow._

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def remoteValidate: Flow[ValidationContext, ValidationContext, NotUsed] =
    Flow[ValidationContext]
      .mapAsync(1) { ctx =>
        val partialValidations: List[PartialValidation] = ctx.experimentResult.results.map {
          case (spec, model: PfaJobResult) =>
            PartialValidation(ctx.query, ctx.experimentResult, spec, Some(model))
          case (spec, _) => PartialValidation(ctx.query, ctx.experimentResult, spec, None)
        }.toList

        Source(partialValidations)
          .filter(_.model.isDefined)
          .buffer(10, OverflowStrategy.backpressure)
          .mapAsync(10)(buildPartialValidation)
          .log("Remote validations")
          .runWith(Sink.seq[PartialValidation])
          .map { validations =>
            val resultsWithValidations: Map[AlgorithmSpec, PfaJobResult] = validations
              .map(
                pv =>
                  pv.algorithmSpec -> pv.model
                    .getOrElse(throw new IllegalStateException("Model should exist"))
              )
              .toMap
            val updatedResults = ctx.experimentResult.results ++ resultsWithValidations
            ctx.copy(experimentResult = ctx.experimentResult.copy(results = updatedResults))
          }
      }

  private def buildPartialValidation(
      partialValidation: PartialValidation
  ): Future[PartialValidation] = {
    val query: MiningQuery = buildMineForValidationQuery(partialValidation)
    logger.info(s"Prepared remote validation query: $query")

    Source
      .single(query)
      .via(dispatcherService.dispatchRemoteMiningFlow)
      .map(_._2)
      .map {
        case QueryResult(_, node, _, shape, _, Some(data), None) if shape == Shapes.score =>
          // Rebuild the spec from the results
          val spec  = ValidationSpec("remote-validation", List(CodeValue("node", node)))
          val score = Right[String, Score](data.convertTo[Score])
          (spec, score)
        case QueryResult(_, node, _, shape, _, None, Some(error)) if shape == Shapes.error =>
          val spec = ValidationSpec("remote-validation", List(CodeValue("node", node)))
          (spec, Left[String, Score](error))
        case otherResult =>
          logger.error(s"Unhandled validation result: $otherResult")
          val spec =
            ValidationSpec("remote-validation", List(CodeValue("node", otherResult.node)))
          (spec, Left[String, Score](s"Unhandled result of shape ${otherResult.`type`}"))
      }
      .map {
        case (spec, score) =>
          partialValidation.copy(model = partialValidation.model.map { m =>
            m.copy(validations = m.validations + (spec -> score))
          })
      }
      .runWith(Sink.last)
  }

  private def buildMineForValidationQuery(v: PartialValidation) = {
    val algorithmDefinition: AlgorithmDefinition = algorithmLookup(v.algorithmSpec.code)
      .valueOr(e => throw new IllegalStateException(e.toList.mkString(",")))

    MiningQuery(
      algorithm = AlgorithmSpec(
        ValidationJob.algorithmCode,
        List(
          CodeValue("model", v.model.map(_.model.compactPrint).getOrElse {
            throw new IllegalArgumentException("Expecting a model")
          }),
          CodeValue("variablesCanBeNull", algorithmDefinition.variablesCanBeNull.toString),
          CodeValue("covariablesCanBeNull", algorithmDefinition.covariablesCanBeNull.toString)
        )
      ),
      executionPlan = None,
      datasets = v.query.validationDatasets,
      user = v.query.user,
      variables = v.query.variables,
      covariables = v.query.covariables,
      grouping = v.query.grouping,
      filters = v.query.filters,
      targetTable = v.query.targetTable
    )
  }

  /*
  ???
    }

    Flow
      .fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
        import GraphDSL.Implicits._

        // prepare graph elements
        val broadcast =
          builder.add(Broadcast[(ValidatedAlgorithmFlow.Job, CoordinatorActor.Response)](2))
        val zip = builder.add(Zip[CoordinatorActor.Response, ValidationResults]())

        // connect the graph
        broadcast.out(0).map(_._2) ~> zip.in0
        broadcast.out(1) ~> dispatchRemoteValidations ~> zip.in1

        FlowShape(broadcast.in, zip.out)
      })
      .log("Remote validation results")
      .named("remote-validate")

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Any",
      "org.wartremover.warts.IsInstanceOf",
      "org.wartremover.warts.Product",
      "org.wartremover.warts.Serializable",
      "org.wartremover.warts.Throw"
    )
  )
  private def dispatchRemoteValidations: Flow[PartialValidation, PartialValidation, NotUsed] =
    Flow[PartialValidation]
      .mapAsync(10) { v: PartialValidation =>

        val algorithmDefinition: AlgorithmDefinition = algorithmLookup(v.algorithmSpec.code).valueOr(e => throw new IllegalStateException(e.toList.mkString(",")))

              val query = MiningQuery(
                algorithm = AlgorithmSpec(
                  ValidationJob.algorithmCode,
                  List(
                    CodeValue("model", v.model.map(_.model.compactPrint).getOrElse {
                      throw new IllegalArgumentException("Expecting a model")
                    }),
                    CodeValue("variablesCanBeNull",
                      algorithmDefinition.variablesCanBeNull.toString),
                    CodeValue("covariablesCanBeNull",
                      algorithmDefinition.covariablesCanBeNull.toString)
                  )
                ),
                executionPlan = None,
                datasets = v.query.validationDatasets,
                user = v.query.user,
                variables = v.query.variables,
                covariables = v.query.covariables,
                grouping = v.query.grouping,
                filters = v.query.filters,
                targetTable = v.query.targetTable
              )

              logger.info(s"Prepared remote validation query: $query")

              // It's ok to drop remote validations that failed, there can be network errors
              // Future alternative: use recover and inject a QueryResult with error, problem is we cannot know
              // which node caused the error
              val decider: Supervision.Decider = {
                case e: Exception =>
                  logger.warn(s"Could not dispatch remote validation query, $e")
                  Supervision.Resume
                case _ => Supervision.Stop
              }
              implicit val system: ActorSystem = context.system
              implicit val materializer: Materializer = ActorMaterializer(
                ActorMaterializerSettings(system).withSupervisionStrategy(decider)
              )
              Source
                .single(query)
                .via(dispatcherService.dispatchRemoteMiningFlow)
                .map(_._2)
                .log("Remote validations")
                .runWith(Sink.seq[QueryResult])
            case other => throw new IllegalArgumentException(s"Unexpected result $other")

          }
      }
      .map[ValidationResults] { l =>
      l.map {
        case QueryResult(_, node, _, shape, _, Some(data), None) if shape == Shapes.score =>
          // Rebuild the spec from the results
          val spec = ValidationSpec("remote-validation", List(CodeValue("node", node)))
          (spec, Right[String, Score](data.convertTo[Score]))
        case QueryResult(_, node, _, shape, _, None, Some(error)) if shape == Shapes.error =>
          val spec = ValidationSpec("remote-validation", List(CodeValue("node", node)))
          (spec, Left[String, Score](error))
        case otherResult =>
          logger.error(s"Unhandled validation result: $otherResult")
          val spec =
            ValidationSpec("remote-validation", List(CodeValue("node", otherResult.node)))
          (spec, Left[String, Score](s"Unhandled result of shape ${otherResult.`type`}"))
      }.toMap

    }
 */

}
