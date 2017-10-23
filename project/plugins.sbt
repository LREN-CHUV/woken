scalacOptions ++= Seq( "-unchecked", "-deprecation" )

resolvers += Classpaths.sbtPluginReleases

resolvers += "Typesafe repository" at "https://dl.bintray.com/typesafe/maven-releases/"

// App Packaging
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.3")

// Benchmarking
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.17")

addSbtPlugin("io.gatling" % "gatling-sbt" % "2.2.1")

// Dependency Resolution
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC3")

// Faster development
addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")

// Code Quality
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.8.0") // scalastyle

addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.4") // scapegoat

addSbtPlugin("com.orrsella" % "sbt-stats" % "1.0.5") // stats

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2") // dependencyGraph

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.0") // dependencyUpdates

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.3.0") // scalariformFormat

addSbtPlugin("com.github.xuwei-k" % "sbt-class-diagram" % "0.1.7")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.3.5")

addSbtPlugin("com.codacy" % "sbt-codacy-coverage" % "1.3.0")

addSbtPlugin("com.updateimpact" % "updateimpact-sbt-plugin" % "2.1.1")
