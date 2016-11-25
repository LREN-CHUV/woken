
name         := "woken-test"

version      := sys.env.get("VERSION")getOrElse("dev")

scalaVersion := "2.11.7"


val versions = new {
  val woken_messages = "a0244b0"
  val akka = "2.3.14"
  val scalaTest = "2.2.5"
}

libraryDependencies ++= {
  Seq(
    "woken-messages"      %%  "woken-messages"   % versions.woken_messages % "test",
    "com.typesafe.akka"   %%  "akka-actor"       % versions.akka           % "test",
    "com.typesafe.akka"   %%  "akka-remote"      % versions.akka           % "test",
    "com.typesafe.akka"   %%  "akka-testkit"     % versions.akka           % "test",
    "org.scalatest"       %%  "scalatest"        % versions.scalaTest      % "test"
  )
}

resolvers += "hbpmip artifactory" at "http://lab01560:9082/artifactory/libs-release/"

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

fork in run := true
