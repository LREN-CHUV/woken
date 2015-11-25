version       := "0.1"

scalaVersion  := "2.11.7"

val versions = new {
  val akka = "2.3.14"
  val spray = "1.3.2"
  val scalaz = "7.1.3"
  val slf4j = "1.7.13"
  val config = "1.2.1"
  val postgres = "9.4-1203-jdbc42"
  val doobie = "0.2.3"
  val scalaTest = "2.2.1"
  val spec2 = "2.3.11"
}

libraryDependencies ++= {
  Seq(
    "io.spray"            %%  "spray-can"        % versions.spray exclude("io.spray", "spray-routing"),
    "io.spray"            %%  "spray-routing-shapeless2" % versions.spray,
    "io.spray"            %%  "spray-json"       % versions.spray,
    "com.typesafe.akka"   %%  "akka-actor"       % versions.akka,
    "org.slf4j"            %  "slf4j-nop"        % versions.slf4j,
    "org.slf4j"            %  "slf4j-api"        % versions.slf4j,
    "org.slf4j"            %  "log4j-over-slf4j" % versions.slf4j, // For Denodo JDBC driver
    "org.scalaz"           %  "scalaz-core_2.11" % versions.scalaz,
    "com.typesafe"         %  "config"           % versions.config,
    "org.postgresql"       %  "postgresql"       % versions.postgres,
    "org.tpolecat"        %%  "doobie-core"      % versions.doobie,
    "com.gettyimages"     %%  "spray-swagger"    % "0.5.0" excludeAll ExclusionRule(organization = "io.spray"),
    "org.webjars"          %  "swagger-ui"       % "2.0.12",
    //---------- Test libraries -------------------//
    "org.scalatest"        %  "scalatest_2.11"   % versions.scalaTest % "test",
    "org.specs2"          %%  "specs2-core"      % versions.spec2     % "test",
    "com.netaporter"      %%  "pre-canned"       % "0.0.7"            % "test",
    "com.typesafe.akka"   %%  "akka-testkit"     % versions.akka      % "test",
    "io.spray"            %%  "spray-testkit"    % versions.spray     % "test"
  )
}

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-Ywarn-dead-code",
  "-language:_",
  "-target:jvm-1.8",
  "-encoding", "UTF-8"
)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

fork in Test := false

parallelExecution in Test := false

Revolver.settings : Seq[sbt.Def.Setting[_]]

fork in run := true

mainClass in Runtime := Some("web.Web")

test in assembly := {} // Do not run tests when building the assembly

net.virtualvoid.sbt.graph.Plugin.graphSettings
