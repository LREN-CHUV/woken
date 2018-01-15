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

package eu.hbp.mip.woken.json

import eu.hbp.mip.woken.messages.external._
import spray.json.{ DefaultJsonProtocol, RootJsonFormat }

object QueryJsonSupport extends DefaultJsonProtocol {

  import formats.OffsetDateTimeJsonFormat

  implicit val formatCodeValue: RootJsonFormat[CodeValue]         = jsonFormat2(CodeValue.apply)
  implicit val formatAlgorithmSpec: RootJsonFormat[AlgorithmSpec] = jsonFormat2(AlgorithmSpec.apply)
  implicit val formatDataSetId: RootJsonFormat[DatasetId]         = jsonFormat1(DatasetId.apply)
  implicit val formatVariableId: RootJsonFormat[VariableId]       = jsonFormat1(VariableId.apply)
  implicit val formatUserId: RootJsonFormat[UserId]               = jsonFormat1(UserId.apply)
  implicit val formatValidationSpec: RootJsonFormat[ValidationSpec] = jsonFormat2(
    ValidationSpec.apply
  )

  implicit val formatMinigQuery: RootJsonFormat[MiningQuery] = jsonFormat7(MiningQuery.apply)
  implicit val formatExperimentQuery: RootJsonFormat[ExperimentQuery] = jsonFormat8(
    ExperimentQuery.apply
  )

  implicit val formatQueryResult: RootJsonFormat[QueryResult] = jsonFormat7(QueryResult.apply)

}
