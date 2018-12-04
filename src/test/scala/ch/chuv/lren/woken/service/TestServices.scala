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

package ch.chuv.lren.woken.service

import akka.actor.ActorRef
import cats.effect.{ ContextShift, IO, Timer }
import cats.effect.internals.IOContextShift
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, VariablesMeta }
import ch.chuv.lren.woken.core.model.database.TableId
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.dao.{
  FeaturesInMemoryRepository,
  FeaturesTableInMemoryRepository,
  MetadataInMemoryRepository,
  WokenInMemoryRepository
}
import ch.chuv.lren.woken.messages.variables.GroupMetaData
import ch.chuv.lren.woken.messages.variables.variablesProtocol._
import spray.json.JsObject

import scala.concurrent.ExecutionContext

object TestServices extends JsonUtils {

  lazy val jobResultService: JobResultService[IO] = JobResultService(
    new WokenInMemoryRepository[IO]().jobResults
  )

  lazy val emptyVariablesMetaService: VariablesMetaService[IO] = {
    VariablesMetaService(
      new MetadataInMemoryRepository[IO]().variablesMeta
    )
  }

  lazy val localVariablesMetaService: VariablesMetaService[IO] = {
    val churnHierarchy = loadJson("/metadata/churn_variables.json").convertTo[GroupMetaData]
    val churnVariablesMeta =
      VariablesMeta(1, "churn", churnHierarchy, "CHURN", List("state", "custserv_calls", "churn"))

    val sampleHierarchy     = loadJson("/metadata/sample_variables.json").convertTo[GroupMetaData]
    val sampleVariablesMeta = VariablesMeta(2, "sample", sampleHierarchy, "SAMPLE_DATA", Nil)

    val cdeHierarchy = loadJson("/metadata/mip_cde_variables.json").convertTo[GroupMetaData]
    val cdeGroupings = List("dataset", "gender", "agegroup", "alzheimerbroadcategory")
    val featuresAVariablesMeta =
      VariablesMeta(3, "cde_features_a", cdeHierarchy, "CDE_FEATURES_A", cdeGroupings)
    val featuresBVariablesMeta =
      VariablesMeta(4, "cde_features_b", cdeHierarchy, "CDE_FEATURES_B", cdeGroupings)
    val featuresCVariablesMeta =
      VariablesMeta(5, "cde_features_c", cdeHierarchy, "CDE_FEATURES_C", cdeGroupings)
    val featuresMixedVariablesMeta =
      VariablesMeta(6, "cde_features_mixed", cdeHierarchy, "CDE_FEATURES_MIXED", cdeGroupings)

    val metaService = VariablesMetaService(
      new MetadataInMemoryRepository[IO]().variablesMeta
    )

    metaService.put(churnVariablesMeta)
    metaService.put(sampleVariablesMeta)
    metaService.put(featuresAVariablesMeta)
    metaService.put(featuresBVariablesMeta)
    metaService.put(featuresCVariablesMeta)
    metaService.put(featuresMixedVariablesMeta)

    metaService
  }

  lazy val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

  val database = "features_db"
  val churnTable = FeaturesTableDescription(TableId(database, None, "CHURN"),
                                            Nil,
                                            None,
                                            validateSchema = false,
                                            None,
                                            0.67)
  val tables: Set[FeaturesTableDescription] = Set(churnTable)
  val tablesContent: Map[TableId, (Headers, List[JsObject])] = Map(
    )
  lazy val emptyFeaturesService: FeaturesService[IO] = FeaturesService(
    new FeaturesInMemoryRepository[IO](database, tables, tablesContent)
  )

  val featuresTableId = TableId("features_db", None, "features_table")

  lazy val emptyFeaturesTableService: FeaturesTableService[IO] = new FeaturesTableServiceImpl(
    new FeaturesTableInMemoryRepository[IO](featuresTableId)
  )

  implicit val ec: ExecutionContext                       = ExecutionContext.global
  implicit lazy val defaultContextShift: ContextShift[IO] = IOContextShift.global
  implicit lazy val defaultTimer: Timer[IO]               = cats.effect.IO.timer(ec)

  def databaseServices(config: WokenConfiguration): DatabaseServices[IO] = {
    val datasetService: DatasetService = ConfBasedDatasetService(config.config)
    val queryToJobService = QueryToJobService(emptyFeaturesService,
                                              localVariablesMetaService,
                                              config.jobs,
                                              config.algorithmLookup)
    DatabaseServices(
      config,
      emptyFeaturesService,
      jobResultService,
      localVariablesMetaService,
      queryToJobService,
      datasetService,
      algorithmLibraryService
    )
  }

  lazy val backendServices: BackendServices =
    BackendServices(dispatcherService = ???, chronosHttp = ???)
}
