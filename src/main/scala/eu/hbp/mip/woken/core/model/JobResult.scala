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

import eu.hbp.mip.woken.core.model.Shapes._
import eu.hbp.mip.woken.messages.external.QueryResult
import spray.json._

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

object JobResult {

  def asQueryResult(jobResult: JobResult): QueryResult =
    jobResult match {
      case pfa: PfaJobResult =>
        QueryResult(
          jobId = pfa.jobId,
          node = pfa.node,
          timestamp = pfa.timestamp,
          shape = pfa_json,
          function = pfa.function,
          data = Some(pfa.model.compactPrint),
          error = None
        )
      case pfa: PfaExperimentJobResult =>
        QueryResult(
          jobId = pfa.jobId,
          node = pfa.node,
          timestamp = pfa.timestamp,
          shape = pfa_json,
          function = pfa.function,
          data = Some(pfa.models.compactPrint),
          error = None
        )
      case v: JsonDataJobResult =>
        QueryResult(
          jobId = v.jobId,
          node = v.node,
          timestamp = v.timestamp,
          shape = v.shape,
          function = v.function,
          data = Some(v.data.compactPrint),
          error = None
        )
      case v: OtherDataJobResult =>
        QueryResult(
          jobId = v.jobId,
          node = v.node,
          timestamp = v.timestamp,
          shape = v.shape,
          function = v.function,
          data = Some(JsString(v.data).compactPrint),
          error = None
        )
      case e: ErrorJobResult =>
        QueryResult(
          jobId = e.jobId,
          node = e.node,
          timestamp = e.timestamp,
          shape = error,
          function = e.function,
          data = None,
          error = Some(e.error)
        )
    }

}
