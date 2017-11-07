
name         := "woken-validation"

version      := sys.env.get("VERSION")getOrElse("dev")

scalaVersion := "2.11.7"


val versions = new {
  val woken_messages = "ee60327"
  val akka = "2.3.16"
  val spray = "1.3.4"
  val spray_json = "1.3.4"
  val spray_routing = "1.3.3"
  val scalaz = "7.1.3"
  val slf4j = "1.7.13"
  val config = "1.2.1"
  val snakeyaml = "1.17"
  val scalaTest = "2.2.5"
  val spec2 = "2.3.11"
  val hadrian = "0.8.4-scala2.11"
  val precanned = "0.0.7"
}

libraryDependencies ++= {
  Seq(
    "woken-messages"      %%  "woken-messages"           % versions.woken_messages,
    "io.spray"            %%  "spray-can"                % versions.spray exclude("io.spray", "spray-routing"),
    "io.spray"            %%  "spray-routing-shapeless2" % versions.spray_routing,
    "io.spray"            %%  "spray-json"               % versions.spray_json,
    "com.typesafe.akka"   %%  "akka-actor"               % versions.akka,
    "com.typesafe.akka"   %%  "akka-remote"              % versions.akka,
    "com.typesafe.akka"   %%  "akka-cluster"             % versions.akka,
    "org.slf4j"           %   "slf4j-nop"                % versions.slf4j,
    "org.slf4j"           %   "slf4j-api"                % versions.slf4j,
    "org.slf4j"           %   "log4j-over-slf4j"         % versions.slf4j, // For Denodo JDBC driver
    "org.scalaz"          %%  "scalaz-core"              % versions.scalaz,
    "com.typesafe"        %   "config"                   % versions.config,
    "org.yaml"            %   "snakeyaml"                % versions.snakeyaml,
    "com.opendatagroup"   %   "hadrian"                  % versions.hadrian,

    //---------- Test libraries -------------------//
    "com.typesafe.akka"   %%  "akka-testkit"     % versions.akka      % "test",
    "org.scalatest"       %%  "scalatest"        % versions.scalaTest % "test",
    "org.specs2"          %%  "specs2-core"      % versions.spec2     % "test",
    "com.netaporter"      %%  "pre-canned"       % versions.precanned % "test",
    "io.spray"            %%  "spray-testkit"    % versions.spray     % "test"
  )
}

resolvers += "hbpmip artifactory" at "http://hbps1.chuv.ch/artifactory/libs-release/"
resolvers += "opendatagroup maven" at "http://repository.opendatagroup.com/maven"

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

//Revolver.settings : Seq[sbt.Def.Setting[_]]

fork in run := true

mainClass in Runtime := Some("eu.hbp.mip.woken.validation.Main")

test in assembly := {} // Do not run tests when building the assembly

net.virtualvoid.sbt.graph.Plugin.graphSettings
