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

package eu.hbp.mip.woken.service

import cats.effect.IO
import eu.hbp.mip.woken.dao.{ MetadataInMemoryRepository, WokenInMemoryRepository }

object TestServices {

  lazy val jobResultService: JobResultService = JobResultService(
    new WokenInMemoryRepository[IO]().jobResults
  )

  lazy val variablesMetaService: VariablesMetaService = VariablesMetaService(
    new MetadataInMemoryRepository[IO]().variablesMeta
  )

  lazy val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

}
