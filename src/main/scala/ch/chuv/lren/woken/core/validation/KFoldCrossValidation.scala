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

import cats.effect.Effect
import ch.chuv.lren.woken.core.features.{ FeaturesQuery, QueryOffset, Sampling }
import ch.chuv.lren.woken.service.FeaturesService
import ch.chuv.lren.woken.fp.runNow
import com.typesafe.scalalogging.LazyLogging
import spray.json.{ JsValue, _ }

import scala.language.higherKinds

trait CrossValidation {

  def partition: Map[Int, QueryOffset]

}

/**
  * K-Fold cross validation
  *
  * @param features The features used for training. Each JsObject contains
  * @param labels The labels used as the source of ground truth
  * @param foldCount Number of folds
  * @see https://en.wikipedia.org/wiki/Cross-validation_(statistics)#k-fold_cross-validation
  */
// TODO: list of features or even labels may be loaded from Woken-validation, here we should just
// generate the SQL queries to access those parts of the dataset
class KFoldCrossValidation(features: List[JsObject], labels: List[JsObject], foldCount: Int)
    extends CrossValidation {

  assert(features.lengthCompare(labels.size) == 0,
         "Features and labels should have the same number of elements")

  /**
    * Generates the partition. It can be empty if the number of folds is greater than the length of the dataset
    *
    * @return a map containing for each fold index the offset and the size of the partition
    */
  override lazy val partition: Map[Int, QueryOffset] = {
    val nb = features.size

    var partition: Map[Int, QueryOffset] = Map()
    if (nb >= foldCount) {
      val t = nb.toFloat / foldCount.toFloat
      for (i: Int <- 0 until foldCount) {
        partition += i -> QueryOffset(scala.math.round(i * t),
                                      scala.math.round((i + 1) * t) - scala.math.round(i * t))
      }
    }
    partition
  }

  /**
    *
    * @param fold
    * @return
    */
  def getTestSet(fold: Int): (List[JsObject], List[JsObject]) =
    (
      features.slice(partition(fold).start, partition(fold).end),
      labels.slice(partition(fold).start, partition(fold).end)
    )

  def groundTruth(fold: Int): List[JsValue] =
    getTestSet(fold)._2.map(x => x.fields.toList.head._2)

  def sampleForFold(fold: Int): Sampling = ???

}

/**
  *
  * Standard k-fold cross validation
  *
  * @author Arnaud Jutzeler
  */
object KFoldCrossValidation extends LazyLogging {

  def apply[F[_]: Effect](
      query: FeaturesQuery,
      foldCount: Int,
      featuresService: FeaturesService[F]
  ): Either[String, KFoldCrossValidation] = {

    logger.info(s"Cross validation query: $query")

    // JSON objects with fieldname corresponding to variables names
    featuresService.featuresTable(query.dbTable).right.map { table =>
      val (_, d) = runNow(table.features(query))
      logger.info(s"Query response: ${d.mkString(",")}")

      // Separate features from labels
      val variables = query.dbVariables
      val features  = query.dbCovariables ++ query.dbGrouping

      logger.info(s"Variables: ${variables.mkString(",")}")
      logger.info(s"Features: ${features.mkString(",")}")

      apply(dataframe = d, variables = variables, features = features, foldCount = foldCount)
    }

  }

  def apply(dataframe: Stream[JsObject],
            variables: List[String],
            features: List[String],
            foldCount: Int): KFoldCrossValidation = {
    val (data, labels) = dataframe.toList
      .map(
        o =>
          (JsObject(o.fields.filterKeys(features.contains(_))),
           JsObject(o.fields.filterKeys(variables.contains(_))))
      )
      .unzip

    logger.info(s"Data: ${data.mkString(",")}")
    logger.info(s"Labels: ${labels.mkString(",")}")

    new KFoldCrossValidation(data, labels, foldCount)
  }
}
