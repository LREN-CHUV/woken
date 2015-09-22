package core.model.results

import spray.json.DefaultJsonProtocol

case class BoxPlotResult(
                          requestId: String,
                          node: String,
                          id: Int,
                          min: Double,
                          q1: Double,
                          median: Double,
                          q3: Double,
                          max: Double
                          )

object BoxPlotResult extends DefaultJsonProtocol {
  implicit val boxPlotResultFormat = lazyFormat(jsonFormat8(BoxPlotResult.apply))
}