scalacOptions ++= Seq( "-unchecked", "-deprecation" )

resolvers += Classpaths.sbtPluginReleases

resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

resolvers += Resolver.bintrayRepo("kamon-io", "sbt-plugins")

libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.25" // Needed by sbt-git
libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25" // Needed by sbt-git

// App Packaging
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.7")

// Dependency Resolution
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0")

// Code Quality
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0") // scalastyle

// addSbtPlugin("com.sksamuel.scapegoat" % "sbt-scapegoat" % "1.3.3") // scapegoat

addSbtPlugin("org.wartremover"   % "sbt-wartremover" % "2.3.6") // Wartremover

// Code formatter
addSbtPlugin("com.lucidchart"    % "sbt-scalafmt"    % "1.15")

// Copyright headers
addSbtPlugin("de.heikoseeberger" % "sbt-header"      % "5.0.0")

// Versioning
addSbtPlugin("com.typesafe.sbt"  % "sbt-git"         % "1.0.0")

// Monitoring
addSbtPlugin("io.kamon" % "sbt-aspectj-runner"       % "1.1.0")
