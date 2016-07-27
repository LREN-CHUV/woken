package core.validation

import spray.json.{JsNumber, JsObject, JsString, JsValue, JsonFormat, _}

import spray.json._


/**
  * Created by Arnaud Jutzeler
  *
  * TODO Add a generic type to score Double, Avro.enum...
  *
  */
trait Scores {

  def update(output: String, label: String): Unit

  def ++(output: List[String], groundTruth: List[JsValue]): Unit = {

    // TODO check type!

    output.zip(groundTruth).foreach({ case (x, y) => {
      this.update(x, y.asJsObject.fields.toList.head._2.toString())
    }
    })
  }
}

object Scores {

  /*def getType(output: List[String], groundTruth: List[JsValue]): String = {

    // TODO it is wrong, there can be case where the class are number...
    // TODO get type elsewhere!
    try {
      Some(output.head.toDouble)
    } catch {
      case _ => return "nominal"
    }

    return "real"
  }*/

  def apply(output: List[String], groundTruth: List[JsValue], variableType: String = "real"): Scores = {

    val score: Scores = variableType match {
      case "nominal" => new ClassificationScores()
      case _ => new RegressionScores()
    }

    output.zip(groundTruth).foreach({ case (x, y) => {
      score.update(x, y.asJsObject.fields.toList.head._2.toString())
    }
    })

    score
  }
}

case class ClassificationScores() extends Scores {

  val classes: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty[String]
  val confusion: scala.collection.mutable.Map[(String, String), Int] = scala.collection.mutable.Map.empty[(String, String), Int]

  override def update(output: String, label: String) = {

    classes.add(output)
    classes.add(label)

    if (!confusion.contains((output, label))) {
      confusion.put((output, label), 0)
    }

    confusion((output, label)) += 1
  }
}

/**
  *
  * TODO Add residual statistics
  *
  * @param `type`
  */
case class RegressionScores(`type`: String = "regression") extends Scores {

  val metrics = Map("MSE" -> new MSE(), "RMSE" -> new RMSE(), "R2" -> new R2(), "FAC2" -> new FAC2())

  override def update(output: String, label: String) = {

    val y = output.toDouble
    val f = label.toDouble

    metrics.foreach(_._2.update(y, f))
  }
}

object ScoresProtocol extends DefaultJsonProtocol {

  implicit object ClassificationScoresJsonFormat extends RootJsonFormat[ClassificationScores] {
    def write(c: ClassificationScores) = {
      // Construct the matrix
      val nb = c.classes.size
      val classes = c.classes.toList.sortWith(_ < _)
      val order = classes.zipWithIndex.toMap
      val confusion_matrix: Array[Array[Int]] = Array.fill(nb, nb)(0)
      c.confusion.foreach({case ((x, y), z) => confusion_matrix(order(x))(order(y)) = z})
      JsObject("classes" -> classes.toJson, "confusion_matrix" -> confusion_matrix.toJson)
    }

    def read(value: JsValue) = value match {
      case _ => deserializationError("To be implemented")
    }
  }

  implicit object RegressionScoresJsonFormat extends RootJsonFormat[RegressionScores] {
    def write(r: RegressionScores) =
      JsObject(r.metrics.mapValues(m => JsNumber(m.get())))

    def read(value: JsValue) = value match {
      case _ => deserializationError("To be implemented")
    }
  }

  implicit object ScoresJsonFormat extends JsonFormat[Scores] {
    def write(s: Scores): JsValue = s match {
      case c: ClassificationScores => c.toJson
      case r: RegressionScores => r.toJson
      case x => x.toJson
    }

    def read(value: JsValue) = {
      // If you need to read, you will need something in the
      // JSON that will tell you which subclass to use
      value.asJsObject.fields("type") match {
        case JsString("classification") => value.convertTo[ClassificationScores]
        case JsString("regression") => value.convertTo[RegressionScores]
      }
    }
  }
}