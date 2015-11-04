import sbt._
import sbt.Keys._
import E2EConfig._

lazy val `e2e-tests` = (project in file("."))
  .settings(e2eTestingSettings )
  .settings(
    scalacOptions += "-language:existentials",
    libraryDependencies ++= Seq(
      "org.yaml" % "snakeyaml" % "1.14",
      "pl.newicom" %% "resttest" % "0.3.2"
    )
  )
  .configs(E2ETest)
  .dependsOn("sales-contracts", "invoicing-contracts", "shipping-contracts")
