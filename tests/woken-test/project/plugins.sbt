scalacOptions ++= Seq( "-unchecked", "-deprecation" )

resolvers += Classpaths.sbtPluginReleases

resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

// App Packaging
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.5")

// Dependency Resolution
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC13")

// Code Quality
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.8.0") // scalastyle

addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.0.4") // scapegoat

addSbtPlugin("org.wartremover"   % "sbt-wartremover" % "2.1.1") // Wartremover

// Code formatter
addSbtPlugin("com.lucidchart"    % "sbt-scalafmt"    % "1.14")

// Copyright headers
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "3.0.2")
