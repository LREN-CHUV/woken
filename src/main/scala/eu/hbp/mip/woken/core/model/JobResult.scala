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

package eu.hbp.mip.woken.core.model

import java.time.OffsetDateTime

import spray.json.{ JsArray, JsObject, JsValue }

sealed trait JobResult extends Product with Serializable {
  def jobId: String
  def node: String
  def timestamp: OffsetDateTime
  def function: String
}

case class PfaJobResult(jobId: String,
                        node: String,
                        timestamp: OffsetDateTime,
                        function: String,
                        model: JsObject)
    extends JobResult {

  def injectCell(name: String, value: JsValue): PfaJobResult = {
    val cells        = model.fields.getOrElse("cells", JsObject()).asJsObject
    val updatedCells = JsObject(cells.fields + (name -> value))
    val updatedModel = JsObject(model.fields + ("cells" -> updatedCells))

    copy(model = updatedModel)
  }

}

case class PfaExperimentJobResult(jobId: String,
                                  node: String,
                                  timestamp: OffsetDateTime,
                                  models: JsArray)
    extends JobResult {

  override val function = "experiment"
}

case class ErrorJobResult(jobId: String,
                          node: String,
                          timestamp: OffsetDateTime,
                          function: String,
                          error: String)
    extends JobResult

sealed trait VisualisationJobResult extends JobResult {
  def shape: String
}

case class JsonDataJobResult(jobId: String,
                             node: String,
                             timestamp: OffsetDateTime,
                             shape: String,
                             function: String,
                             data: JsObject)
    extends VisualisationJobResult

case class OtherDataJobResult(jobId: String,
                              node: String,
                              timestamp: OffsetDateTime,
                              shape: String,
                              function: String,
                              data: String)
    extends VisualisationJobResult
