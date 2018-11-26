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
import ch.chuv.lren.woken.core.model.AlgorithmDefinition
import ch.chuv.lren.woken.core.model.jobs.{ ExperimentJobResult, PfaJobResult, ValidationJob }
import ch.chuv.lren.woken.messages.APIJsonProtocol
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.service.DispatcherService
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.{ ExecutionContext, Future }
import APIJsonProtocol._

object RemoteValidationFlow {

  case class ValidationContext(query: ExperimentQuery,
                               algorithms: Map[AlgorithmSpec, AlgorithmDefinition],
                               experimentResult: ExperimentJobResult)
  case class PartialValidation(query: ExperimentQuery,
                               experimentResult: ExperimentJobResult,
                               algorithmSpec: AlgorithmSpec,
                               algorithmDefinition: AlgorithmDefinition,
                               model: Option[PfaJobResult])

}

case class RemoteValidationFlow(
    dispatcherService: DispatcherService,
    context: ActorContext
)(implicit materializer: Materializer, ec: ExecutionContext)
    extends LazyLogging {

  import RemoteValidationFlow._

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  def remoteValidate: Flow[ValidationContext, ValidationContext, NotUsed] =
    Flow[ValidationContext]
      .mapAsync(1) { ctx =>
        def definitionOf(spec: AlgorithmSpec) =
          ctx.algorithms.getOrElse(
            spec,
            throw new IllegalStateException(s"Expected a definition matching algorithm spec $spec")
          )
        val partialValidations: List[PartialValidation] = ctx.experimentResult.results.map {
          case (spec, model: PfaJobResult) =>
            PartialValidation(ctx.query,
                              ctx.experimentResult,
                              spec,
                              definitionOf(spec),
                              Some(model))
          case (spec, _) =>
            PartialValidation(ctx.query, ctx.experimentResult, spec, definitionOf(spec), None)
        }.toList

        Source(partialValidations)
          .filter(_.model.isDefined)
          .buffer(10, OverflowStrategy.backpressure)
          .mapAsync(10)(buildPartialValidation)
          .mapConcat(identity)
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
  ): Future[List[PartialValidation]] = {
    val query: MiningQuery = buildMineForValidationQuery(partialValidation)
    logger.info(s"Prepared remote validation query: $query")

    Source
      .single(query)
      .via(dispatcherService.dispatchRemoteMiningFlow)
      .fold(List[QueryResult]()) {
        _ :+ _._2
      }
      .map { l: List[QueryResult] =>
        l.map {
            case QueryResult(_, node, _, shape, _, Some(data), None, _) if shape == Shapes.score =>
              // Rebuild the spec from the results
              val spec  = ValidationSpec("remote-validation", List(CodeValue("node", node)))
              val score = Right[String, Score](data.convertTo[Score])
              (spec, score)
            case QueryResult(_, node, _, shape, _, None, Some(error), _) if shape == Shapes.error =>
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
      }
      .runWith(Sink.last)
  }

  private def buildMineForValidationQuery(v: PartialValidation) = MiningQuery(
    algorithm = AlgorithmSpec(
      ValidationJob.algorithmCode,
      List(
        // Take the raw model, as model contains runtime-inserted validations which are not yet compliant with PFA / Avro spec
        CodeValue("model", v.model.map(_.modelWithoutValidation.compactPrint).getOrElse {
          throw new IllegalArgumentException("Expecting a model")
        }),
        CodeValue("variablesCanBeNull", v.algorithmDefinition.variablesCanBeNull.toString),
        CodeValue("covariablesCanBeNull", v.algorithmDefinition.covariablesCanBeNull.toString)
      ),
      None
    ),
    executionPlan = None,
    datasets = v.query.validationDatasets.diff(dispatcherService.localDatasets),
    user = v.query.user,
    variables = v.query.variables,
    covariables = v.query.covariables,
    covariablesMustExist = true,
    grouping = v.query.grouping,
    filters = v.query.filters,
    targetTable = v.query.targetTable
  )

}
