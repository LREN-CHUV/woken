package eu.hbp.mip.woken.core.validation

import org.scalactic.TolerantNumerics
import org.scalatest.{FlatSpec, Matchers}

class ScoresTest extends FlatSpec with Matchers {

  "BinaryClassificationScores " should "be correct" in {

    import ScoresProtocol._
    import spray.json._
    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.001)

    val scores = new BinaryClassificationScores(List("a", "b"))

    val f = List[String](
      "\"a\"",
      "\"a\"",
      "\"b\"",
      "\"b\"",
      "\"b\"",
      "\"a\""
    )
    val y = List[String](
      "\"a\"",
      "\"b\"",
      "\"a\"",
      "\"b\"",
      "\"a\"",
      "\"a\""
    )

    scores.compute(f, y)

    val json_object = scores.toJson

    json_object.asJsObject.fields("Confusion matrix").compactPrint should equal ("{\"labels\":[\"a\",\"b\"],\"values\":[[2.0,2.0],[1.0,1.0]]}")
    json_object.asJsObject.fields("Accuracy").convertTo[Double] should equal (0.5)
    json_object.asJsObject.fields("Precision").convertTo[Double] should equal (2/3.0)
    json_object.asJsObject.fields("Recall").convertTo[Double] should equal (0.5)
    json_object.asJsObject.fields("F1-score").convertTo[Double] should equal (0.5714)
    json_object.asJsObject.fields("False positive rate").convertTo[Double] should equal (0.5)
  }

  /*"BinaryClassificationThresholdScore " should "be correct" in {

    import org.scalactic.TolerantNumerics
    import spray.json._
    import core.validation.ScoresProtocol._
    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.001)

    val scores = new BinaryClassificationThresholdScores()

    val f = List[String](
      "{\"a\": 0.8, \"b\": 0.2}",
      "{\"a\": 0.8, \"b\": 0.2}",
      "{\"a\": 0.3, \"b\": 0.7}",
      "{\"a\": 0.0, \"b\": 1.0}",
      "{\"a\": 0.4, \"b\": 0.6}",
      "{\"a\": 0.55, \"b\": 0.45}"
    )
    val y = List[String](
      "\"a\"",
      "\"b\"",
      "\"a\"",
      "\"b\"",
      "\"a\"",
      "\"a\""
    )

    scores.compute(f, y)

    val json_object = scores.toJson

    println(scores)

    json_object.asJsObject.fields.get("Accuracy").get.convertTo[Double] should equal (0.5)
    json_object.asJsObject.fields.get("Precision").get.convertTo[Double] should equal (2/3.0)
    json_object.asJsObject.fields.get("Recall").get.convertTo[Double] should equal (0.5)
    json_object.asJsObject.fields.get("F-measure").get.convertTo[Double] should equal (0.5714)

  }*/

  "ClassificationScore " should "be correct" in {

    import ScoresProtocol._
    import spray.json._
    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.001)

    val scores = new ClassificationScores(List("a", "b", "c"))

    val f = List[String](
      "\"a\"",
      "\"c\"",
      "\"b\"",
      "\"b\"",
      "\"c\"",
      "\"a\""
    )
    val y = List[String](
      "\"a\"",
      "\"c\"",
      "\"a\"",
      "\"b\"",
      "\"a\"",
      "\"b\""
    )

    scores.compute(f, y)

    val json_object = scores.toJson

    json_object.asJsObject.fields("Confusion matrix").compactPrint should equal ("{\"labels\":[\"a\",\"b\",\"c\"],\"values\":[[1.0,1.0,1.0],[1.0,1.0,0.0],[0.0,0.0,1.0]]}")
    json_object.asJsObject.fields("Accuracy").convertTo[Double] should equal (0.5)
    json_object.asJsObject.fields("Weighted Recall").convertTo[Double] should equal (0.5) // a:1/3 (3), b: 1/2 (2), c:1/1 (1)
    json_object.asJsObject.fields("Weighted Precision").convertTo[Double] should equal (0.5) // a:1/2 (3), b:1/2 (2), c:1/2 (1)
    json_object.asJsObject.fields("Weighted F1-score").convertTo[Double] should equal (0.47777) // a:2/5 (3), b:1/2 (2), c:2/3 (1)
    json_object.asJsObject.fields("Weighted false positive rate").convertTo[Double] should equal (0.2833) // a:1/3 (3), b:1/4 (2), c:1/5 (1)
  }

  "RegressionScores " should "be correct" in {

    import ScoresProtocol._
    import spray.json._
    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)

    val scores = new RegressionScores()

    val f = List[String]("15.6", "0.0051", "23.5", "0.421", "1.2", "0.0325")
    val y = List[String]("123.56", "0.67", "1078.42", "64.2", "1.76", "1.23")

    scores.compute(f, y)

    val json_object = scores.toJson

    json_object.asJsObject.fields("R-squared").convertTo[Double] should equal (-0.2352)
    json_object.asJsObject.fields("RMSE").convertTo[Double] should equal (433.7)
    json_object.asJsObject.fields("MSE").convertTo[Double] should equal (188096.919)
    json_object.asJsObject.fields("MAE").convertTo[Double] should equal (204.8469)
    json_object.asJsObject.fields("Explained variance").convertTo[Double] should equal (42048.9776) // E(y) = 211.64, SSreg = 252293.8657
  }

  "RegressionScores 2" should "be correct" in {

    import ScoresProtocol._
    import spray.json._
    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)

    val scores = new RegressionScores()

    val f = List[String]("165.3", "1.65", "700.23", "66.7", "0.5", "2.3")
    val y = List[String]("123.56", "0.67", "1078.42", "64.2", "1.76", "1.23")

    scores.compute(f, y)

    val json_object = scores.toJson

    json_object.asJsObject.fields("R-squared").convertTo[Double] should equal (0.84153)
    json_object.asJsObject.fields("RMSE").convertTo[Double] should equal (155.34)
    json_object.asJsObject.fields("MSE").convertTo[Double] should equal (24129.974)
    json_object.asJsObject.fields("MAE").convertTo[Double] should equal (70.9566)
    json_object.asJsObject.fields("Explained variance").convertTo[Double] should equal (65729.0537) // E(y) = 211.64, SSreg = 394374.3226
  }
}