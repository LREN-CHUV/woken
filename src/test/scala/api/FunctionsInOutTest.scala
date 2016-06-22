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

    val jobResult = JobResult("001", "testNode", OffsetDateTime.now(), Some(results), shape = "r_other_intermediate", function = "r-summary-stats")

  }

  object RLinearRegression {
    val results =
    """
      |{"coefficients\":[0.00919402,-5.856e-06],\"residuals\":[{\"(Intercept)\":1.56301203e-08,\"feature_nameHippocampus_R\":-1.56301203e-08,\"_row\":\"(Intercept)\"},{\"(Intercept)\":-1.56301203e-08,\"feature_nameHippocampus_R\":3.12602405e-08,\"_row\":\"feature_nameHippocampus_R\"}],\"anova\":[{}],\"summary\":{\"coefficients\":[{\"Estimate\":0.00919402,\"Std. Error\":0.00012502,\"t value\":73.54014356,\"Pr(>|t|)\":1.50505882e-87,\"_row\":\"(Intercept)\"},{\"Estimate\":-5.856e-06,\"Std. Error\":0.00017681,\"t value\":-0.03312111,\"Pr(>|t|)\":0.97364537,\"_row\":\"feature_nameHippocampus_R\"}],\"aliased\":[false,false],\"sigma\":0.00088403,\"df\":[2,98,2],\"r_squared\":0.00001119,\"adj_r_squared\":-0.01019277,\"cov_unscaled\":[{\"(Intercept)\":0.02,\"feature_nameHippocampus_R\":-0.02,\"_row\":\"(Intercept)\"},{\"(Intercept)\":-0.02,\"feature_nameHippocampus_R\":0.04,\"_row\":\"feature_nameHippocampus_R\"}]}}
    """.stripMargin

    val jobResult = JobResult("002", "testNode", OffsetDateTime.now(), Some(results), shape = "r_other_intermediate", function = "r-linear-regression")
  }
}

/*class FunctionsInOutTest extends FlatSpec with Matchers {

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

}*/
