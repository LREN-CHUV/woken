package core.validation

import org.scalatest._

class ScoresTest extends FlatSpec with Matchers {

  // TODO Test JSON representation of ClassificationScore and RegressionScore

  "Metrics " should "be correct" in {

    import org.scalactic.TolerantNumerics
    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.01)

    var r2 = new R2()
    var rmse = new RMSE()

    var y = Array[Double](123.56, 0.67, 1078.42, 64.2, 1.76, 1.23)
    var f = Array[Double](15.6, 0.0051, 23.5, 0.421, 1.2, 0.0325)

    y.zip(f).foreach({case (y, f) => r2.update(y, f); rmse.update(y, f)})

    r2.get() should equal (0.0)
    rmse.get() should equal (433.7)

    r2 = new R2()
    rmse = new RMSE()

    y = Array[Double](123.56, 0.67, 1078.42, 64.2, 1.76, 1.23)
    f = Array[Double](165.3, 1.65, 700.23, 66.7, 0.5, 2.3)

    y.zip(f).foreach({case (y, f) => r2.update(y, f); rmse.update(y, f)})

    r2.get() should equal (0.84153)
    rmse.get() should equal (155.34)
  }
}