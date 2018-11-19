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

import cats.effect.IO
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.dao.{
  FeaturesInMemoryRepository,
  MetadataInMemoryRepository,
  WokenInMemoryRepository
}
import ch.chuv.lren.woken.messages.variables.GroupMetaData
import ch.chuv.lren.woken.messages.variables.variablesProtocol._
import ch.chuv.lren.woken.util.JsonUtils

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

  lazy val emptyFeaturesService: FeaturesService[IO] = FeaturesService(
    new FeaturesInMemoryRepository[IO](Set())
  )
}
