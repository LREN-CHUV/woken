package core.validation

import com.opendatagroup.hadrian.datatype.{AvroEnum, AvroString}
import com.opendatagroup.hadrian.jvmcompiler.PFAEngine
import spray.json.{JsNumber, JsObject, JsString, JsValue, JsonFormat, _}

/**
  * Created by Arnaud Jutzeler
  *
  * TODO Add a generic type to score Double, Avro.enum...
  *
  */
trait Scores {

  def update(output: String, label: JsObject): Unit

  def ++(model: String, data: List[JsObject], labels: List[JsObject]): Unit = {

    // Load the PFA file into a scoring engine
    val engine = PFAEngine.fromJson(model).head

    // Make an iterator for the input data
    val inputData = engine.jsonInputIterator[AnyRef](data.map(o => o.compactPrint).iterator)

    inputData.zip(labels.iterator).foreach({ case (x, y) => {

      this.update(engine.jsonOutput(engine.action(x)), y)
    }
    })
  }

}

object Scores {

  def apply(model: String, data: List[JsObject], labels: List[JsObject]): Scores = {

    // Load the PFA file into a scoring engine
    val engine = PFAEngine.fromJson(model).head

    val score: Scores = engine.outputType match {
      case s: AvroString => new ClassificationScores()
      case _ => new RegressionScores()
    }

    // Make an iterator for the input data
    val inputData = engine.jsonInputIterator[AnyRef](data.map(o => o.compactPrint).iterator)

    inputData.zip(labels.iterator).foreach({ case (x, y) => {

      score.update(engine.jsonOutput(engine.action(x)), y)
    }
    })

    score
  }

  def apply(model: String): Scores = {

    // Load the PFA file into a scoring engine
    val engine = PFAEngine.fromJson(model).head

    val score: Scores = engine.outputType match {
      case s: AvroString => new ClassificationScores()
      case _ => new RegressionScores()
    }

    score
  }
}

//TODO Add accuracy, precision, sensitivity, ...
//TODO Have a specific BinaryClassificationScores?
case class ClassificationScores() extends Scores {

  val classes: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty[String]
  val confusion: scala.collection.mutable.Map[(String, String), Int] = scala.collection.mutable.Map.empty[(String, String), Int]

  override def update(output: String, label: JsObject) = {

    import spray.json.DefaultJsonProtocol._
    val output_string = JsonParser(output).convertTo[String]
    val label_string = label.fields.values.head.convertTo[String]

    classes.add(output_string)
    classes.add(label_string)

    if (!confusion.contains((output_string, label_string))) {
      confusion.put((output_string, label_string), 0)
    }

    confusion((output_string, label_string)) += 1
  }
}

case class RegressionScores(`type`: String = "regression") extends Scores {

  val metrics = Map("MSE" -> new MSE(), "RMSE" -> new RMSE(), "R2" -> new R2(), "FAC2" -> new FAC2())

  override def update(output: String, label: JsObject) = {

    val y = output.toDouble
    val f = label.fields.values.head.prettyPrint.toDouble

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