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

package eu.hbp.mip.woken.dao

import java.sql.{ Connection, DriverManager, ResultSet, ResultSetMetaData }

import cats._
import cats.data._
import doobie._
import eu.hbp.mip.woken.config.DatabaseConfiguration
import org.postgresql.util.PGobject
import spray.json._

import scala.language.higherKinds

/**
  * Data Access Layer
  */
object DAL {

  implicit val JsObjectMeta: Meta[JsObject] =
    Meta
      .other[PGobject]("json")
      .xmap[JsObject](
        a => a.getValue.parseJson.asJsObject, // failure raises an exception
        a => {
          val o = new PGobject
          o.setType("json")
          o.setValue(a.compactPrint)
          o
        }
      )
}
trait DAL {}

/**
  * Data access to features used by machine learning and visualisation algorithms
  */
case class FeaturesDAL(featuresDbConnection: DatabaseConfiguration) extends DAL {

  // TODO: Doobie provides better tools...

  lazy val ldsmConnection: Connection = {
    Class.forName(featuresDbConnection.jdbcDriver)
    DriverManager.getConnection(featuresDbConnection.jdbcUrl,
                                featuresDbConnection.user,
                                featuresDbConnection.password)
  }

  case class ColumnMeta(index: Int, label: String, datatype: String)

  def runQuery(dbConnection: Connection, query: String): (List[ColumnMeta], Stream[JsObject]) = {
    // A transaction that sets a seed
    // TODO The seed must be passed as a query parameters and generated above
    dbConnection.setAutoCommit(false)
    dbConnection.prepareStatement("SELECT setseed(0.67)").execute()
    val rs = dbConnection.prepareStatement(query).executeQuery
    dbConnection.commit()
    implicit val cols = getColumnMeta(rs.getMetaData)
    (cols, getStreamOfResults(rs))
  }

  /**
    * Returns a list of columns for specified ResultSet which describes column properties we are interested in.
    */
  def getColumnMeta(rsMeta: ResultSetMetaData): List[ColumnMeta] =
    (for {
      idx <- (1 to rsMeta.getColumnCount)
      colName = rsMeta.getColumnLabel(idx).toLowerCase
      colType = rsMeta.getColumnClassName(idx)
    } yield ColumnMeta(idx, colName, colType)).toList

  /**
    * Creates a stream of results on top of a ResultSet.
    */
  def getStreamOfResults(rs: ResultSet)(implicit cols: List[ColumnMeta]): Stream[JsObject] =
    new Iterator[JsObject] {
      def hasNext = rs.next
      def next() =
        rowToObj(rs)
    }.toStream

  /**
    * Given a row from a ResultSet produces a JSON document.
    */
  def rowToObj(rs: ResultSet)(implicit cols: List[ColumnMeta]): JsObject = {
    val fields = for {
      ColumnMeta(index, label, datatype) <- cols
      clazz = Class.forName(datatype)
      value = columnValueGetter(datatype, index, rs)
    } yield label -> value
    JsObject(fields: _*)
  }

  /**
    * Takes a fully qualified Java type as String and returns one of the subtypes of JValue by fetching a value
    * from result set and converting it to proper type.
    * It supports only the most common types and everything else that does not match this conversion is converted
    * to String automatically. If you see that you results should contain more specific type instead of String
    * add conversion cases to {{{resultsetGetters}}} map.
    */
  def columnValueGetter(datatype: String, columnIdx: Int, rs: ResultSet): JsValue = {
    val obj = rs.getObject(columnIdx)
    if (obj == null)
      JsNull
    else {
      val converter = resultsetGetters getOrElse (datatype, (obj: Object) => JsString(obj.toString))
      converter(obj)
    }
  }

  val resultsetGetters: Map[String, Object => JsValue] = Map(
    "java.lang.Integer"    -> ((obj: Object) => JsNumber(obj.asInstanceOf[Int])),
    "java.lang.Long"       -> ((obj: Object) => JsNumber(obj.asInstanceOf[Long])),
    "java.lang.Double"     -> ((obj: Object) => JsNumber(obj.asInstanceOf[Double])),
    "java.lang.Float"      -> ((obj: Object) => JsNumber(obj.asInstanceOf[Float])),
    "java.lang.BigInteger" -> ((obj: Object) => JsNumber(obj.asInstanceOf[java.math.BigInteger])),
    "java.math.BigDecimal" -> ((obj: Object) => JsNumber(obj.asInstanceOf[java.math.BigDecimal])),
    "java.math.Boolean"    -> ((obj: Object) => JsBoolean(obj.asInstanceOf[Boolean])),
    "java.lang.String"     -> ((obj: Object) => JsString(obj.asInstanceOf[String]))
  )

  val resultsetJsTypes: Map[String, JsString] = Map(
    "java.lang.Integer"    -> JsString("number"),
    "java.lang.Long"       -> JsString("number"),
    "java.lang.Double"     -> JsString("number"),
    "java.lang.Float"      -> JsString("number"),
    "java.math.BigInteger" -> JsString("number"),
    "java.math.BigDecimal" -> JsString("number"),
    "java.lang.Boolean"    -> JsString("boolean"),
    "java.lang.String"     -> JsString("string")
  )

  def queryData(featuresTable: String, columns: Seq[String]): JsObject = {
    val (meta, data) = runQuery(
      ldsmConnection,
      s"select ${columns.mkString(",")} from $featuresTable where ${columns.map(_ + " is not null").mkString(" and ")}"
    )
    JsObject(
      "doc"   -> JsString(s"Raw data for variables ${meta.map(_.label).mkString(", ")}"),
      "input" -> JsString("null"),
      "output" -> JsObject("type" -> JsString("array"),
                           "items" -> JsObject("type" -> JsString("row"))),
      "cells" -> JsObject(
        "data" ->
          JsObject(
            "type" -> JsObject(
              "type" -> JsString("array"),
              "items" -> JsObject(
                "type" -> JsString("record"),
                "name" -> JsString("row"),
                "fields" -> JsArray(
                  meta.map(
                    col =>
                      JsObject("name" -> JsString(col.label),
                               "type" -> resultsetJsTypes(col.datatype))
                  ): _*
                )
              )
            ),
            "init" -> JsArray(data.toVector)
          )
      ),
      "action" -> JsArray(JsObject("cell" -> JsString("data")))
    )
  }
}
