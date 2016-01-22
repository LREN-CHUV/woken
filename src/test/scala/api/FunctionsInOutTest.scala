package api

import java.time.OffsetDateTime

import core.model.JobResult
import org.scalatest.{FlatSpec, Matchers}

object FunctionsInOutTest {

  object RSummaryStatistics {

    val results =
      """
        |{"tissue1_volume":[[0.0068],[0.0086],[0.0093],[0.0098],[0.0115],[0.0092],[0.0009],[0.9191],[100]],"_row":["min","q1","median","q3","max","mean","std","sum","count"]}
      """.stripMargin

    val jobResult = JobResult("001", "testNode", OffsetDateTime.now(), Some(results))

  }

  object RLinearRegression {
    val results =
    """
      |{"coefficients":[1.51756892,-1.91151546],"residuals":{"(Intercept)":["NA","NA"],"tissue1_volume":["NA","NA"],"_row":["(Intercept)","tissue1_volume"]}}
    """.stripMargin

    val jobResult = JobResult("002", "testNode", OffsetDateTime.now(), Some(results))
  }
}

class FunctionsInOutTest extends FlatSpec with Matchers {

  import FunctionsInOut._
  import FunctionsInOutTest._

  "Results coming from a summary statistics calculation in R" should "be converted to a dataset in the format accepted by Virtua" in {
    val dataset = summaryStatsResult2Dataset(RSummaryStatistics.jobResult)

    dataset.isRight shouldBe true

    val ds = dataset.right.get

    ds.code shouldBe "001"
    ds.header.compactPrint shouldBe """ [["min","q1","median","q3","max","mean","std","sum","count"]] """.trim
    ds.data.compactPrint shouldBe """ {"tissue1_volume":[0.0068,0.0086,0.0093,0.0098,0.0115,0.0092,0.0009,0.9191,100]} """.trim

  }

  "Results coming from a linear regression calculation in R" should "be converted to a dataset in the format accepted by Virtua" in {
    val dataset = linearRegressionResult2Dataset(RLinearRegression.jobResult)

    dataset.isRight shouldBe true

    val ds = dataset.right.get

    ds.code shouldBe "002"
    ds.header.compactPrint shouldBe """ {"coefficients":["(Intercept)","tissue1_volume"],"residuals":[["(Intercept)","tissue1_volume"]]} """.trim
    ds.data.compactPrint shouldBe """ {"coefficients":[1.51756892,-1.91151546],"residuals":{"(Intercept)":["NA","NA"],"tissue1_volume":["NA","NA"]}} """.trim

  }

}
