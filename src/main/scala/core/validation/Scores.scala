package core.validation

import com.opendatagroup.hadrian.datatype.AvroEnum
import com.opendatagroup.hadrian.jvmcompiler.PFAEngine
import spray.json.DefaultJsonProtocol._
import spray.json.{JsObject, JsString, JsValue, JsonFormat, _}

object ClassificationScores {
  implicit val classificationScoresFormat = jsonFormat2(ClassificationScores.apply)
}

object RegressionScores {
  implicit val regressionScoresFormat = jsonFormat5(RegressionScores.apply)
}

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

      //average.update(engine.jsonOutput(engine.action(x)), y)
      this.update(engine.jsonOutput(engine.action(x)), y)
    }
    })
  }

}

object Scores {

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

  def apply(model: String, data: List[JsObject], labels: List[JsObject]): Scores = {

    // Load the PFA file into a scoring engine
    val engine = PFAEngine.fromJson(model).head

    val score: Scores = engine.outputType match {
      case enum: AvroEnum => new ClassificationScores(enum.symbols)
      case _ => new RegressionScores()
    }

    // Make an iterator for the input data
    val inputData = engine.jsonInputIterator[AnyRef](data.map(o => o.compactPrint).iterator)

    inputData.zip(labels.iterator).foreach({ case (x, y) => {

      //average.update(engine.jsonOutput(engine.action(x)), y)
      score.update(engine.jsonOutput(engine.action(x)), y)
    }
    })

    score
  }

  def apply(model: String): Scores = {

    // Load the PFA file into a scoring engine
    val engine = PFAEngine.fromJson(model).head

    val score: Scores = engine.outputType match {
      case enum: AvroEnum => new ClassificationScores(enum.symbols)
      case _ => new RegressionScores()
    }

    score
  }
}

case class ClassificationScores(symbols: Seq[String], val `type`: String = "classification") extends Scores {

  private val order = symbols.toList.zipWithIndex map {case (l, i) => l -> i } toMap
  private val confusionMatrix: Array[Array[Int]] = Array.tabulate(symbols.size, symbols.size)((x, y) => 0)

  override def update(output: String, label: JsObject) = {

    val value = label.fields.values.head.prettyPrint

    confusionMatrix(order(output))(order(value)) += 1
  }
}

case class RegressionScores(var mse: Double = 0.0, var rmse: Double = 0.0, var r2: Double = 0.0, var fac2: Double = 0.0, val `type`: String = "regression") extends Scores {

  override def update(output: String, label: JsObject) = {

    val value = label.fields.values.head.prettyPrint.toDouble

    //TODO Implement correct metrics
    mse += scala.math.abs(output.toDouble - value)
    rmse += scala.math.abs(output.toDouble - value)
    r2 += scala.math.abs(output.toDouble - value)
    fac2 += scala.math.abs(output.toDouble - value)
  }
}
