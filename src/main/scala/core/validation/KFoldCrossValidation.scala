package core.validation

import core.ValidationActor
import dao.LdsmDAL
import spray.json.{JsValue, _}

trait CrossValidation {

  def partition: Map[String, (Int, Int)]

}

/**
  * TODO In WP3 should be an Actor
  *
  * @param data
  * @param k
  */
class KFoldCrossValidation(data: Stream[JsObject], labels: Stream[JsObject], k: Int) extends CrossValidation {

  /**
    *
    * @return
    */
  override def partition: Map[String, (Int, Int)] = {
    val nb = data.size
    var partition: Map[String, (Int, Int)] = Map()
    if (nb >= k) {
      val t = nb.toFloat / k.toFloat
      for (i: Int <- 0 to k - 1) {
        partition += i.toString -> Tuple2(scala.math.round(i * t), (scala.math.round((i + 1) * t)) - scala.math.round(i * t))
      }
    }
    partition
  }

  /**
    *
    * @param k
    * @return
    */
  private def getTestSet(k : String): List[JsValue] = {
    return data.toList.slice(partition(k)._1, partition(k)._1 + partition(k)._2)
  }

  /**
    * TODO To be changed in WP3
    *
    * @param models
    * @return
    */
  def validate(models: Map[String, String]): JsValue = {

    var folds: Map[String, JsValue] = Map()
    val aggregate: Scores = Scores(models.head._2)

    partition.foreach({case (k, (s, n)) => {
      folds += k -> Scores(models(k), data.toList.slice(s, s + n), labels.toList.slice(s, s + n)).toJson
      aggregate.++(models(k), data.toList.slice(s, s + n), labels.toList.slice(s, s + n))
    }
    })

    import core.validation.Scores.ScoresJsonFormat
    JsObject("type" -> JsString("KFoldCrossValidation"), "average" -> aggregate.toJson, "folds" -> JsObject(folds))
  }
}

/**
  *
  * Standard k-fold cross validation
  *
  * @author Arnaud Jutzeler
  */
object KFoldCrossValidation {

  def apply(job: ValidationActor.Job, k: Int) = {

    val conf = config.Config.dbConfig(job.inputDb.get)
    val dal = new LdsmDAL(conf.jdbcDriver, conf.jdbcUrl, conf.jdbcUser, conf.jdbcPassword, "")

    // JSON objects with fieldname corresponding to variables names
    val (_, d) = dal.runQuery(dal.ldsmConnection, job.parameters("PARAM_query"))

    // Separate features from labels
    val variables = job.parameters("PARAM_variables").split(",")
    val features = job.parameters("PARAM_covariables").split(",") ++ job.parameters("PARAM_grouping").split(",")

    val (data, labels) = d.map(o => (JsObject(o.fields.filterKeys(features.contains(_))), JsObject(o.fields.filterKeys(variables.contains(_))))).unzip

    new KFoldCrossValidation(data, labels, k)
  }
}