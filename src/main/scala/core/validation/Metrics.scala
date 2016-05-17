package core.validation

/**
  * Created by Arnaud Jutzeler
  */
trait Metric {

  def update(y: Double, f: Double): Unit
  def get(): Double

}

class MSE() extends Metric {

  var sum: Double = 0.0
  var n: Int = 0

  def update(y: Double, f: Double) = {
    sum += (y - f) * (y - f)
    n += 1
  }

  def get() = {
    sum / n
  }
}

class RMSE() extends MSE {

  override def get() = {
    math.sqrt(sum / n)
  }
}

/**
  * Based on Knuth online variance algorithm
  *
  */
class R2() extends Metric {

  var n: Int = 0
  var yMean: Double = 0.0
  var M2: Double = 0.0
  var diff: Double = 0.0

  def update(y: Double, f: Double) = {

    // SStot
    n += 1
    val delta = y - yMean
    yMean += delta / n
    M2 += delta * (y - yMean)

    // SSres
    diff += (y - f) * (y - f)
  }

  def get(): Double = {

    if (n < 2) {
      return Double.NaN
    }

    scala.math.max(1 - (diff / M2), 0.0)
  }
}

class FAC2() extends Metric {

  var in: Int = 0
  var n: Int = 0

  def update(y: Double, f: Double) = {
    if ((f > 0.5 * y) && (f < 2 * y)) in += 1 else {}
    n += 1
  }

  def get(): Double = {

    if(n == 0) {
      return Double.NaN
    }

    in.toDouble / n.toDouble
  }
}