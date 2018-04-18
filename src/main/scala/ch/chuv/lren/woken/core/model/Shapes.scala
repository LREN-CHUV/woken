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

package ch.chuv.lren.woken.core.model

// TODO: move to woken-messages

/**
  * List of shapes (formats) for the results produced by the algorithms
  */
object Shapes {

  /**
    * The shape of data returned by an algorithm
    */
  sealed trait Shape {

    /**
      * @return the MIME type for the shape
      */
    def mime: String

    /**
      * @return the list of possible values identifying this shape
      */
    def values: Set[String]

    /**
      * Checks if the string is an identifier of the current shape
      *
      * @param s The string to check
      * @return true if this shape can be identified by s
      */
    def isIdentifiedBy(s: String): Boolean = values.contains(s)
  }

  object error extends Shape {
    val error  = "error"
    val mime   = "text/plain+error"
    val values = Set(error, mime)
  }

  /**
    * Portable Format for Analytics (PFA)
    * @see http://dmg.org/pfa/
    */
  object pfa extends Shape {
    val pfa    = "pfa"
    val json   = "pfa_json"
    val mime   = "application/pfa+json"
    val values = Set(pfa, json, mime)
  }

  /**
    * PFA encoded in Yaml
    * @see http://dmg.org/pfa/
    */
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

  /**
    * Tabular data resource
    * @see https://frictionlessdata.io/specs/tabular-data-resource/
    */
  object tabularDataResource extends Shape {
    val json   = "tabular_data_resource_json"
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
    val oldMime    = "application/highcharts+json"
    val mime       = "application/vnd.highcharts+json"
    val values     = Set(highcharts, json, oldMime, mime)
  }

  object visjs extends Shape {
    val visjs  = "visjs"
    val js     = "visjs_javascript"
    val mime   = "application/vnd.visjs+javascript"
    val values = Set(visjs, js, mime)
  }

  object plotly extends Shape {
    val plotly = "plotly"
    val json   = "plotly_json"
    val mime   = "application/vnd.plotly.v1+json"
    val values = Set(plotly, json, mime)
  }

  object vega extends Shape {
    val vega   = "vega"
    val json   = "vega_json"
    val mime   = "application/vnd.vega+json"
    val values = Set(vega, json, mime)
  }

  object vegaLite extends Shape {
    val vegaLite = "vegaLite"
    val json     = "vega_lite_json"
    val mime     = "application/vnd.vegalite+json"
    val values   = Set(vegaLite, json, mime)
  }

  /**
    * Generic Json, for other types of visualisations
    */
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

  /** Serialization of an object in Python using pickle */
  object pythonPickle extends Shape {
    val pythonPickle = "python_pickle"
    val mime         = "application/octet-stream+python-pickle;base64"
    val values       = Set(pythonPickle, mime)
  }

  /** Serialization of an object in Java using Java serialization */
  object javaSerialization extends Shape {
    val javaSerialization = "java_serialization"
    val mime              = "application/java-serialized-object;base64"
    val values            = Set(javaSerialization, mime)
  }

  /** Serialization of an object in R using saveRDS */
  object rdsSerialization extends Shape {
    val rdsSerialization = "rds_serialization"
    val mime             = "application/octet-stream+rds;base64"
    val values           = Set(rdsSerialization, mime)
  }

  /** Results stored as PFA documents in the database */
  val pfaResults: Set[Shape] = Set(pfa, pfaYaml, pfaExperiment)

  /** Results stored as Json documents in the database */
  val visualisationJsonResults: Set[Shape] =
    Set(highcharts, plotly, vega, vegaLite, tabularDataResource, json, compound)

  /** Results stored as generic documents (strings) in the database */
  val visualisationOtherResults: Set[Shape] = Set(html, svg, png, visjs)

  /** Results containing a model stored in a native serialization formant in the database */
  val serializedModelsResults: Set[Shape] =
    Set(pythonPickle, javaSerialization, rdsSerialization)

  val allResults
    : Set[Shape] = pfaResults ++ visualisationJsonResults ++ visualisationOtherResults ++ serializedModelsResults + error

  def fromString(s: String): Option[Shape] =
    allResults.find(_.isIdentifiedBy(s))

}
