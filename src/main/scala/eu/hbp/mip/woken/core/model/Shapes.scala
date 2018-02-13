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

object Shapes {

  sealed trait Shape {
    def mime: String
    def values: Set[String]
    def contains(s: String): Boolean = values.contains(s)
  }

  object error extends Shape {
    val error  = "error"
    val mime   = "text/plain+error"
    val values = Set(error, mime)
  }

  object pfa extends Shape {
    val pfa    = "pfa"
    val json   = "pfa_json"
    val mime   = "application/pfa+json"
    val values = Set(pfa, json, mime)
  }

  object pfaYaml extends Shape {
    val yaml   = "pfa_yaml"
    val mime   = "application/pfa+yaml"
    val values = Set(yaml, mime)
  }

  object pfaExperiment extends Shape {
    val json   = "pfa_experiment_json"
    val mime   = "application/vnd.hbp.mip.experiment.pfa+json"
    val values = Set(json, mime)
  }

  object dataResource extends Shape {
    val json   = "data_resource_json"
    val mime   = "application/vnd.dataresource+json"
    val values = Set(json, mime)
  }

  object html extends Shape {
    val html   = "html"
    val mime   = "text/html"
    val values = Set(html, mime)
  }

  object svg extends Shape {
    val svg       = "svg"
    val svg_image = "svg_image"
    val mime      = "image/svg+xml"
    val values    = Set(svg, svg_image, mime)
  }

  object png extends Shape {
    val png       = "png"
    val png_image = "png_image"
    val mime      = "image/png;base64"
    val values    = Set(png, png_image, mime)
  }

  object highcharts extends Shape {
    val highcharts = "highcharts"
    val json       = "highcharts_json"
    val mime       = "application/highcharts+json"
    val values     = Set(highcharts, json, mime)
  }

  object visjs extends Shape {
    val visjs  = "visjs"
    val js     = "visjs_javascript"
    val mime   = "application/visjs+javascript"
    val values = Set(visjs, js, mime)
  }

  object plotly extends Shape {
    val plotly = "plotly"
    val json   = "plotly_json"
    val mime   = "application/plotly+json"
    val values = Set(plotly, json, mime)
  }

  // Generic Json, for other types of visualisations
  object json extends Shape {
    val json   = "json"
    val mime   = "application/json"
    val values = Set(json, mime)
  }

  object compound extends Shape {
    val compound = "compound"
    val mime     = "application/vnd.hbp.mip.compound+json"
    val values   = Set(compound, mime)
  }

  /** Group results stored as Json documents in the base */
  val visualisationJson: Set[Shape] = Set(highcharts, plotly, json, dataResource)
  def getVisualisationJson(s: String): Option[Shape] =
    visualisationJson.find(_.contains(s))

  /** Group results stored as generic documents (strings) in the base */
  val visualisationOther: Set[Shape] = Set(html, svg, png, visjs)
  def getVisualisationOther(s: String): Option[Shape] =
    visualisationOther.find(_.contains(s))

}
