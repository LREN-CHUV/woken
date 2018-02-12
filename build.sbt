// *****************************************************************************
// Projects
// *****************************************************************************

lazy val `woken` =
  project
    .in(file("."))
    .enablePlugins(AutomateHeaderPlugin, GitVersioning, GitBranchPrompt)
    .settings(settings)
    .settings(
      Seq(
        mainClass in Runtime := Some("eu.hbp.mip.woken.web.Web"),
        libraryDependencies ++= Seq(
          library.akkaActor,
          library.akkaRemote,
          library.akkaCluster,
          library.akkaClusterTools,
          library.akkaStream,
          library.akkaContrib,
          library.akkaSlf4j,
          //library.akkaTracingCore,
          //library.akkaTracingAkkaHttp,
          library.akkaHttp,
          library.akkaHttpJson,
          library.akkaHttpSwagger,
          library.swaggerUI,
          library.sprayJson,
          library.slf4j,
          library.log4jSlf4j,
          library.disruptor,
          library.catsCore,
          library.kittens,
          library.config,
          library.doobieCore,
          library.doobiePostgres,
          library.doobieHikari,
          library.yaml,
          library.hadrian,
          library.wokenMessages,
          //library.scalaCache,
          library.scalaLogging,
          library.scalaCheck   % Test,
          library.scalaTest    % Test,
          library.akkaTestkit  % Test
        ),
        includeFilter in (Compile, unmanagedResources) := "*.xml" || "*.conf" || "*.html",
        includeFilter in (Test, unmanagedResources) := "*.json" || "*.conf",
        assemblyJarName in assembly := "woken-all.jar"
      )
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {
    object Version {
      val scalaCheck      = "1.13.5"
      val scalaTest       = "3.0.3"
      val akka            = "2.5.9"
      val akkaTracing     = "0.6.1"
      val akkaHttp        = "10.0.11"
      val akkaHttpSwagger = "0.11.0"
      val swaggerUI       = "2.0.12"
      val sprayJson       = "1.3.4"
      val slf4j           = "1.7.25"
      val log4j           = "2.10.0"
      val disruptor       = "3.3.7"
      val cats            = "1.0.1"
      val kittens         = "1.0.0-RC2"
      val config          = "1.2.1"
      val doobie          = "0.5.0-M13"
      val snakeyaml       = "1.17"
      val hadrian         = "0.8.5"
      val wokenMessages   = "2.3.2"
      val scalaCache      = "0.21.0"
      val scalaLogging    = "3.7.2"
    }
    val scalaCheck: ModuleID   = "org.scalacheck"    %% "scalacheck"   % Version.scalaCheck
    val scalaTest: ModuleID    = "org.scalatest"     %% "scalatest"    % Version.scalaTest
    val akkaActor: ModuleID    = "com.typesafe.akka" %% "akka-actor"   % Version.akka
    val akkaRemote: ModuleID   = "com.typesafe.akka" %% "akka-remote"  % Version.akka
    val akkaCluster: ModuleID  = "com.typesafe.akka" %% "akka-cluster" % Version.akka
    val akkaClusterTools: ModuleID = "com.typesafe.akka" %% "akka-cluster-tools" % Version.akka
    val akkaStream: ModuleID   = "com.typesafe.akka" %% "akka-stream"  % Version.akka
    val akkaContrib: ModuleID  = "com.typesafe.akka" %% "akka-contrib" % Version.akka
    val akkaSlf4j: ModuleID    = "com.typesafe.akka" %% "akka-slf4j"   % Version.akka
    val akkaTestkit: ModuleID  = "com.typesafe.akka" %% "akka-testkit" % Version.akka
    val akkaTracingCore: ModuleID     = "com.github.levkhomich" %% "akka-tracing-core" % Version.akkaTracing
    val akkaTracingAkkaHttp: ModuleID = "com.github.levkhomich" %% "akka-tracing-http" % Version.akkaTracing
    val akkaHttp: ModuleID = "com.typesafe.akka" %% "akka-http" % Version.akkaHttp
    val akkaHttpJson: ModuleID = "com.typesafe.akka" %% "akka-http-spray-json" % Version.akkaHttp
    val akkaHttpSwagger: ModuleID = "com.github.swagger-akka-http"   %% "swagger-akka-http" % Version.akkaHttpSwagger
    val swaggerUI: ModuleID    = "org.webjars"        % "swagger-ui"   % Version.swaggerUI
    val sprayJson: ModuleID    = "io.spray"          %% "spray-json"   % Version.sprayJson
    val slf4j: ModuleID        = "org.slf4j"          % "slf4j-api"    % Version.slf4j
    val log4jSlf4j: ModuleID   = "org.apache.logging.log4j" % "log4j-slf4j-impl" % Version.log4j
    val disruptor: ModuleID    = "com.lmax"           % "disruptor"    % Version.disruptor
    val catsCore: ModuleID     = "org.typelevel"     %% "cats-core"    % Version.cats
    val kittens: ModuleID      = "org.typelevel"     %% "kittens"      % Version.kittens
    val config: ModuleID       = "com.typesafe"       % "config"       % Version.config
    val doobieCore: ModuleID   = "org.tpolecat"      %% "doobie-core"  % Version.doobie
    val doobiePostgres: ModuleID = "org.tpolecat"    %% "doobie-postgres" % Version.doobie
    val doobieHikari: ModuleID = "org.tpolecat"      %% "doobie-hikari" % Version.doobie
    val yaml: ModuleID         = "org.yaml"           % "snakeyaml"    % Version.snakeyaml
    val hadrian: ModuleID      = "com.opendatagroup" %  "hadrian"      % Version.hadrian
    val scalaCache: ModuleID   = "com.github.cb372"  %% "scalacache-core" % Version.scalaCache
    val scalaLogging: ModuleID = "com.typesafe.scala-logging" %% "scala-logging" % Version.scalaLogging
    val wokenMessages: ModuleID = "eu.humanbrainproject.mip" %% "woken-messages" % Version.wokenMessages
  }

resolvers += "HBPMedical Bintray Repo" at "https://dl.bintray.com/hbpmedical/maven/"
resolvers += "opendatagroup maven" at "http://repository.opendatagroup.com/maven"

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings = commonSettings ++ gitSettings ++ scalafmtSettings

lazy val commonSettings =
  Seq(
    scalaVersion := "2.11.11",
    organization in ThisBuild := "eu.humanbrainproject.mip",
    organizationName in ThisBuild := "Human Brain Project MIP by LREN CHUV",
    homepage in ThisBuild := Some(url(s"https://github.com/HBPMedical/${name.value}/#readme")),
    licenses in ThisBuild := Seq("Apache-2.0" ->
      url(s"https://github.com/sbt/${name.value}/blob/${version.value}/LICENSE")),
    startYear in ThisBuild := Some(2017),
    description in ThisBuild := "Woken - a FaaS for machine learning",
    developers in ThisBuild := List(
      Developer("ludovicc", "Ludovic Claude", "@ludovicc", url("https://github.com/ludovicc"))
    ),
    scmInfo in ThisBuild := Some(ScmInfo(url(s"https://github.com/HBPMedical/${name.value}"), s"git@github.com:HBPMedical/${name.value}.git")),
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      //"-Xlint", -- disabled due to Scala bug, waiting for 2.12.5
      "-Yno-adapted-args",
      //"-Ywarn-dead-code", -- disabled due to Scala bug, waiting for 2.12.5
      //"-Ywarn-value-discard", -- disabled due to Scala bug, waiting for 2.12.5
      "-Ypartial-unification",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding",
      "UTF-8"
    ),
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
    unmanagedSourceDirectories.in(Compile) := Seq(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Seq(scalaSource.in(Test).value),
    wartremoverWarnings in (Compile, compile) ++= Warts.unsafe,
    fork in run := true,
    test in assembly := {},
    fork in Test := false,
    parallelExecution in Test := false
  )

lazy val gitSettings =
  Seq(
    git.gitTagToVersionNumber := { tag: String =>
      if (tag matches "[0-9]+\\..*") Some(tag)
      else None
    },
    git.useGitDescribe := true
  )

lazy val scalafmtSettings =
  Seq(
    scalafmtOnCompile := true,
    scalafmtOnCompile.in(Sbt) := false,
    scalafmtVersion := "1.4.0"
  )
