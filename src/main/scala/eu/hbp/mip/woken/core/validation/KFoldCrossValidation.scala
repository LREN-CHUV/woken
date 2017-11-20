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

import eu.hbp.mip.woken.core.CrossValidationActor
import eu.hbp.mip.woken.dao.LdsmDAL
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

  def apply(job: CrossValidationActor.Job, foldCount: Int): KFoldCrossValidation = {

    val conf = eu.hbp.mip.woken.config.WokenConfig.dbConfig(job.inputDb.get)
    val dal  = new LdsmDAL(conf.jdbcDriver, conf.jdbcUrl, conf.jdbcUser, conf.jdbcPassword, "")

    // JSON objects with fieldname corresponding to variables names
    val (_, d) = dal.runQuery(dal.ldsmConnection, job.parameters("PARAM_query"))

    // Separate features from labels
    val variables = job.parameters("PARAM_variables").split(",")
    val features = job.parameters("PARAM_covariables").split(",") ++ job
      .parameters("PARAM_grouping")
      .split(",")

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
