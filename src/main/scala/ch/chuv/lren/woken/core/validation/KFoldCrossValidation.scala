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

package ch.chuv.lren.woken.core.validation

import ch.chuv.lren.woken.core.features.FeaturesQuery
import ch.chuv.lren.woken.dao.FeaturesDAL
import spray.json.{ JsValue, _ }

trait CrossValidation {

  def partition: Map[Int, (Int, Int)]

}

/**
  * TODO In WP3 should be an Actor
  *
  * @param data
  * @param foldCount
  */
class KFoldCrossValidation(data: Stream[JsObject], labels: Stream[JsObject], foldCount: Int)
    extends CrossValidation {

  /**
    *
    * @return
    */
  // TODO: return None if data size < fold count
  override def partition: Map[Int, (Int, Int)] = {
    val nb                              = data.size
    var partition: Map[Int, (Int, Int)] = Map()
    if (nb >= foldCount) {
      val t = nb.toFloat / foldCount.toFloat
      for (i: Int <- 0 until foldCount) {
        partition += i -> Tuple2(scala.math.round(i * t),
                                 scala.math.round((i + 1) * t) - scala.math.round(i * t))
      }
    }
    partition
  }

  /**
    *
    * @param k
    * @return
    */
  def getTestSet(k: Int): (List[JsValue], List[JsValue]) =
    (
      data.toList.slice(partition(k)._1, partition(k)._1 + partition(k)._2),
      labels.toList.slice(partition(k)._1, partition(k)._1 + partition(k)._2)
    )

  def groundTruth(fold: Int): List[JsValue] =
    getTestSet(fold)._2.map(x => x.asJsObject.fields.toList.head._2)

}

/**
  *
  * Standard k-fold cross validation
  *
  * @author Arnaud Jutzeler
  */
object KFoldCrossValidation {

  def apply(query: FeaturesQuery,
            foldCount: Int,
            featuresDAL: FeaturesDAL): KFoldCrossValidation = {

    val sql = query.query

    // JSON objects with fieldname corresponding to variables names
    val (_, d) = featuresDAL.runQuery(featuresDAL.ldsmConnection, sql)

    // Separate features from labels
    val variables = query.dbVariables
    val features  = query.dbCovariables ++ query.dbGrouping

    val (data, labels) = d
      .map(
        o =>
          (JsObject(o.fields.filterKeys(features.contains(_))),
           JsObject(o.fields.filterKeys(variables.contains(_))))
      )
      .unzip

    new KFoldCrossValidation(data, labels, foldCount)
  }
}
