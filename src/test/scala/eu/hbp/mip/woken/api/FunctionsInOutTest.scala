package eu.hbp.mip.woken.api

import java.time.OffsetDateTime

import eu.hbp.mip.woken.core.model.JobResult

/**
  * Created by ludovic on 23/08/17.
  */
object FunctionsInOutTest {

  object RSummaryStatistics {

    val results: String =
      """
        |{"tissue1_volume":[[0.0068],[0.0086],[0.0093],[0.0098],[0.0115],[0.0092],[0.0009],[0.9191],[100]],"_row":["min","q1","median","q3","max","mean","std","sum","count"]}
      """.stripMargin

    val jobResult = JobResult("001", "testNode", OffsetDateTime.now(), Some(results), shape = "r_other_intermediate", function = "r-summary-stats")

  }

  object RLinearRegression {
    val results: String =
    """
      |{"coefficients\":[0.00919402,-5.856e-06],\"residuals\":[{\"(Intercept)\":1.56301203e-08,\"feature_nameHippocampus_R\":-1.56301203e-08,\"_row\":\"(Intercept)\"},{\"(Intercept)\":-1.56301203e-08,\"feature_nameHippocampus_R\":3.12602405e-08,\"_row\":\"feature_nameHippocampus_R\"}],\"anova\":[{}],\"summary\":{\"coefficients\":[{\"Estimate\":0.00919402,\"Std. Error\":0.00012502,\"t value\":73.54014356,\"Pr(>|t|)\":1.50505882e-87,\"_row\":\"(Intercept)\"},{\"Estimate\":-5.856e-06,\"Std. Error\":0.00017681,\"t value\":-0.03312111,\"Pr(>|t|)\":0.97364537,\"_row\":\"feature_nameHippocampus_R\"}],\"aliased\":[false,false],\"sigma\":0.00088403,\"df\":[2,98,2],\"r_squared\":0.00001119,\"adj_r_squared\":-0.01019277,\"cov_unscaled\":[{\"(Intercept)\":0.02,\"feature_nameHippocampus_R\":-0.02,\"_row\":\"(Intercept)\"},{\"(Intercept)\":-0.02,\"feature_nameHippocampus_R\":0.04,\"_row\":\"feature_nameHippocampus_R\"}]}}
    """.stripMargin

    val jobResult = JobResult("002", "testNode", OffsetDateTime.now(), Some(results), shape = "r_other_intermediate", function = "r-linear-regression")
  }
}
