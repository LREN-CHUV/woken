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

import akka.actor.ActorSystem
import cats.effect._
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.backends.faas.AlgorithmExecutor
import ch.chuv.lren.woken.backends.worker.WokenWorker
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.model.database.FeaturesTableDescription
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.dao._
import ch.chuv.lren.woken.errors.ErrorReporter
import ch.chuv.lren.woken.messages.datasets.TableId
import ch.chuv.lren.woken.config.ConfigurationInstances._
import ch.chuv.lren.woken.Predefined.FeaturesTable._
import ch.chuv.lren.woken.Predefined.VariablesMetas._

import org.scalamock.scalatest.MockFactory
import spray.json.JsObject

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object TestServices extends JsonUtils with MockFactory {

  lazy val wokenRepository: WokenRepository[IO]               = new WokenInMemoryRepository[IO]()
  lazy val jobResultService: JobResultRepository[IO]          = wokenRepository.jobResults
  lazy val resultsCacheRepository: ResultsCacheRepository[IO] = wokenRepository.resultsCache

  lazy val emptyVariablesMetaService: VariablesMetaRepository[IO] =
    new MetadataInMemoryRepository[IO]().variablesMeta

  lazy val localVariablesMetaService: VariablesMetaRepository[IO] = {

    val metaService = new MetadataInMemoryRepository[IO]().variablesMeta

    metaService.put(churnVariablesMeta)
    metaService.put(sampleVariablesMeta)
    metaService.put(featuresAVariablesMeta)
    metaService.put(featuresBVariablesMeta)
    metaService.put(featuresCVariablesMeta)
    metaService.put(featuresMixedVariablesMeta)

    metaService
  }

  lazy val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

  val tables: Map[TableId, FeaturesTableDescription] =
    Set(sampleTable, cdeTable).map(t => (t.table, t)).toMap
  val tablesContent: Map[TableId, (Headers, List[JsObject])] = Map(
    sampleTable.table -> (sampleHeaders -> sampleData),
    cdeTable.table    -> (cdeHeaders    -> cdeData)
  )

  lazy val featuresService: FeaturesService[IO] = FeaturesService(
    new FeaturesInMemoryRepository[IO](featuresDbConfiguration, tablesContent)
  )

  val emptyFeaturesTableId = TableId(featuresDb.code, "empty_table")
  val emptyFeatureTable =
    FeaturesTableDescription(emptyFeaturesTableId, Nil, None, validateSchema = false, None, 0.67)

  lazy val emptyFeaturesTableService: FeaturesTableService[IO] = new FeaturesTableServiceImpl(
    new FeaturesTableInMemoryRepository[IO](emptyFeatureTable, Nil, None, Nil)
  )

  implicit val ec: ExecutionContext                       = ExecutionContext.global
  implicit lazy val defaultContextShift: ContextShift[IO] = IO.contextShift(ec)
  implicit lazy val defaultTimer: Timer[IO]               = cats.effect.IO.timer(ec)

  def databaseServices(config: WokenConfiguration): DatabaseServices[IO] = {
    val datasetService: DatasetService = ConfBasedDatasetService(config.config, config.jobs)
    val queryToJobService = QueryToJobService(featuresService,
                                              localVariablesMetaService,
                                              config.jobs,
                                              config.algorithmLookup)
    DatabaseServices(
      config,
      featuresService,
      jobResultService,
      resultsCacheRepository,
      localVariablesMetaService,
      queryToJobService,
      datasetService,
      algorithmLibraryService
    )
  }

  lazy val dispatcherService: DispatcherService     = mock[DispatcherService]
  lazy val wokenWorker: WokenWorker[IO]             = mock[WokenWorker[IO]]
  lazy val algorithmExecutor: AlgorithmExecutor[IO] = mock[AlgorithmExecutor[IO]]
  lazy val errorReporter: ErrorReporter             = mock[ErrorReporter]

  def backendServices(system: ActorSystem): BackendServices[IO] =
    BackendServices(
      dispatcherService = dispatcherService,
      algorithmExecutor = algorithmExecutor,
      wokenWorker = wokenWorker,
      errorReporter = errorReporter
    )
}
