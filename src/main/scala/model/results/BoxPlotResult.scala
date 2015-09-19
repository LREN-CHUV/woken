package model.results

case class BoxPlotResult(
                          requestId: String,
                          id: Int,
                          min: Double,
                          q1: Double,
                          median: Double,
                          q3: Double,
                          max: Double
                          )

