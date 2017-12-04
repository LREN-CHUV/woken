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

package eu.hbp.mip.woken.core.validation

import eu.hbp.mip.woken.api.FunctionsInOut
import eu.hbp.mip.woken.backends.FeaturesHelper
import eu.hbp.mip.woken.core.CrossValidationActor
import eu.hbp.mip.woken.dao.FeaturesDAL
import spray.json.{ JsValue, _ }

trait CrossValidation {

  def partition: Map[String, (Int, Int)]

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
  override def partition: Map[String, (Int, Int)] = {
    val nb                                 = data.size
    var partition: Map[String, (Int, Int)] = Map()
    if (nb >= foldCount) {
      val t = nb.toFloat / foldCount.toFloat
      for (i: Int <- 0 until foldCount) {
        partition += i.toString -> Tuple2(scala.math.round(i * t),
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
  def getTestSet(k: String): (List[JsValue], List[JsValue]) =
    (
      data.toList.slice(partition(k)._1, partition(k)._1 + partition(k)._2),
      labels.toList.slice(partition(k)._1, partition(k)._1 + partition(k)._2)
    )
}

/**
  *
  * Standard k-fold cross validation
  *
  * @author Arnaud Jutzeler
  */
object KFoldCrossValidation {

  def apply(job: CrossValidationActor.Job,
            foldCount: Int,
            featuresDAL: FeaturesDAL): KFoldCrossValidation = {
    import FunctionsInOut._

    val query = job.query
    // TODO: shouldn't cross validation exclude here a portion of the feature dataset?
    val sql = FeaturesHelper.buildQueryFeaturesSql(job.inputTable, query, None)

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
