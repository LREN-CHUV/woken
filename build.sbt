import sbt.ExclusionRule
import sbtassembly.MergeStrategy

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
        mainClass in Runtime := Some("ch.chuv.lren.woken.web.Web"),
        libraryDependencies ++= Seq(
          library.akkaActor,
          //library.akkaActorTyped,
          library.akkaRemote,
          library.akkaCluster,
          library.akkaClusterTools,
          library.akkaStream,
          library.akkaContrib,
          library.akkaSlf4j,
          library.akkaHttp,
          library.akkaHttpCors,
          library.akkaHttpJson,
          library.kamon,
          library.kamonAkka,
          library.kamonAkkaHttp,
          library.kamonAkkaRemote,
          library.kamonPrometheus,
          library.kamonZipkin,
          library.kamonSystemMetrics,
          library.kamonSigar,
          library.akkaHttpSwagger,
          library.swaggerJaxrs,
          library.swaggerUI,
          library.sprayJson,
          library.slf4j,
          library.log4jSlf4j,
          library.disruptor,
          library.scalaLogging,
          library.catsCore,
          library.kittens,
          library.config,
          library.doobieCore,
          library.doobiePostgres,
          library.doobieHikari,
          library.yaml,
          library.wokenMessages,
          //library.scalaCache,
          library.scalaCheck             % Test,
          library.scalaTest              % Test,
          library.scalaMock              % Test,
          library.akkaTestkit            % Test,
          library.akkaStreamTestkit      % Test,
          library.doobieScalaTest        % Test,
          library.catsScalaTest          % Test,
          library.dockerTestKitScalaTest % Test,
          library.dockerTestKitSpotify   % Test
        ),
        includeFilter in (Compile, unmanagedResources) := "*.json" || "*.conf" || "*.xml" || "*.html",
        includeFilter in (Test, unmanagedResources) := "*.json" || "*.conf",
        // Use the customMergeStrategy in your settings
        assemblyMergeStrategy in assembly := customMergeStrategy,
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
      val scalaTest       = "3.0.5"
      val scalaMock       = "4.1.0"
      val akka            = "2.5.12"
      val akkaHttp        = "10.1.1"
      val akkaHttpCors    = "0.3.0"
      val akkaHttpSwagger = "0.14.0"
      val kamon           = "1.1.2"
      val kamonAkka       = "1.0.1"
      val kamonAkkaRemote = "1.0.1"
      val kamonAkkaHttp   = "1.1.0"
      val kamonReporter   = "1.0.0"
      val kamonSystemMetrics = "1.0.0"
      val kamonSigar      = "1.6.6-rev002"
      val swaggerJaxrs    = "1.5.19"
      val swaggerUI       = "3.13.3"
      val sprayJson       = "1.3.4"
      val slf4j           = "1.7.25"
      val log4j           = "2.11.0"
      val disruptor       = "3.4.2"
      val scalaLogging    = "3.9.0"
      val cats            = "1.1.0"
      val kittens         = "1.0.0-RC3"
      val catsScalaTest   = "2.3.1"
      val config          = "1.3.3"
      val doobie          = "0.5.2"
      val snakeyaml       = "1.23"
      val scalaCache      = "0.21.0"
      val dockerTestKit   = "0.9.6"
      val wokenMessages   = "2.8.1"
    }
    object ExclusionRules {
      val excludeLogback = ExclusionRule(organization = "ch.qos.logback", name = "logback-classic")
    }
    val scalaCheck: ModuleID   = "org.scalacheck"    %% "scalacheck"   % Version.scalaCheck
    val scalaTest: ModuleID    = "org.scalatest"     %% "scalatest"    % Version.scalaTest
    val scalaMock:ModuleID     = "org.scalamock"     %% "scalamock"    % Version.scalaMock
    val akkaActor: ModuleID    = "com.typesafe.akka" %% "akka-actor"   % Version.akka
    val akkaActorTyped: ModuleID = "com.typesafe.akka" %% "akka-actor-typed" % Version.akka
    val akkaRemote: ModuleID   = "com.typesafe.akka" %% "akka-remote"  % Version.akka
    val akkaCluster: ModuleID  = "com.typesafe.akka" %% "akka-cluster" % Version.akka
    val akkaClusterTools: ModuleID = "com.typesafe.akka" %% "akka-cluster-tools" % Version.akka
    val akkaStream: ModuleID   = "com.typesafe.akka" %% "akka-stream"  % Version.akka
    val akkaContrib: ModuleID  = "com.typesafe.akka" %% "akka-contrib" % Version.akka
    val akkaSlf4j: ModuleID    = "com.typesafe.akka" %% "akka-slf4j"   % Version.akka
    val akkaTestkit: ModuleID  = "com.typesafe.akka" %% "akka-testkit" % Version.akka
    val akkaStreamTestkit: ModuleID   = "com.typesafe.akka" %% "akka-stream-testkit" % Version.akka
    val akkaHttp: ModuleID     = "com.typesafe.akka" %% "akka-http" % Version.akkaHttp
    val akkaHttpJson: ModuleID = "com.typesafe.akka" %% "akka-http-spray-json" % Version.akkaHttp
    val akkaHttpCors: ModuleID = "ch.megard"         %% "akka-http-cors" % Version.akkaHttpCors
    val akkaHttpSwagger: ModuleID = "com.github.swagger-akka-http"   %% "swagger-akka-http" % Version.akkaHttpSwagger

    // Kamon
    val kamon: ModuleID        = "io.kamon" %% "kamon-core" % Version.kamon excludeAll ExclusionRules.excludeLogback
    val kamonAkka: ModuleID    = "io.kamon" %% "kamon-akka-2.5" % Version.kamonAkka excludeAll ExclusionRules.excludeLogback
    val kamonAkkaRemote: ModuleID = "io.kamon" %% "kamon-akka-remote-2.5" % Version.kamonAkkaRemote excludeAll ExclusionRules.excludeLogback
    val kamonAkkaHttp: ModuleID = "io.kamon" %% "kamon-akka-http-2.5" % Version.kamonAkkaHttp excludeAll ExclusionRules.excludeLogback
    val kamonSystemMetrics: ModuleID = "io.kamon" %% "kamon-system-metrics" % Version.kamonSystemMetrics excludeAll ExclusionRules.excludeLogback
    val kamonPrometheus: ModuleID = "io.kamon" %% "kamon-prometheus" % Version.kamonReporter excludeAll ExclusionRules.excludeLogback
    val kamonZipkin: ModuleID  =  "io.kamon" %% "kamon-zipkin" % Version.kamonReporter excludeAll ExclusionRules.excludeLogback
    val kamonSigar: ModuleID   = "io.kamon"           % "sigar-loader" % Version.kamonSigar

    val swaggerJaxrs: ModuleID  = "io.swagger"        % "swagger-jaxrs" % Version.swaggerJaxrs
    val swaggerUI: ModuleID    = "org.webjars"        % "swagger-ui"   % Version.swaggerUI
    val sprayJson: ModuleID    = "io.spray"          %% "spray-json"   % Version.sprayJson
    val slf4j: ModuleID        = "org.slf4j"          % "slf4j-api"    % Version.slf4j
    val log4jSlf4j: ModuleID   = "org.apache.logging.log4j" % "log4j-slf4j-impl" % Version.log4j
    val disruptor: ModuleID    = "com.lmax"           % "disruptor"    % Version.disruptor
    val scalaLogging: ModuleID = "com.typesafe.scala-logging" %% "scala-logging" % Version.scalaLogging
    val catsCore: ModuleID     = "org.typelevel"     %% "cats-core"    % Version.cats
    val kittens: ModuleID      = "org.typelevel"     %% "kittens"      % Version.kittens
    val catsScalaTest: ModuleID = "com.ironcorelabs" %% "cats-scalatest" % Version.catsScalaTest
    val config: ModuleID       = "com.typesafe"       % "config"       % Version.config
    val doobieCore: ModuleID   = "org.tpolecat"      %% "doobie-core"  % Version.doobie
    val doobiePostgres: ModuleID = "org.tpolecat"    %% "doobie-postgres" % Version.doobie
    val doobieHikari: ModuleID = "org.tpolecat"      %% "doobie-hikari" % Version.doobie
    val doobieScalaTest: ModuleID = "org.tpolecat" %% "doobie-scalatest" % Version.doobie
    val yaml: ModuleID         = "org.yaml"           % "snakeyaml"    % Version.snakeyaml
    val scalaCache: ModuleID   = "com.github.cb372"  %% "scalacache-core" % Version.scalaCache
    val dockerTestKitScalaTest: ModuleID = "com.whisk" %% "docker-testkit-scalatest" % Version.dockerTestKit excludeAll ExclusionRules.excludeLogback
    val dockerTestKitSpotify: ModuleID = "com.whisk" %% "docker-testkit-impl-spotify" % Version.dockerTestKit excludeAll ExclusionRules.excludeLogback
    val wokenMessages: ModuleID = "ch.chuv.lren.woken" %% "woken-messages" % Version.wokenMessages

  }

resolvers += "HBPMedical Bintray Repo" at "https://dl.bintray.com/hbpmedical/maven/"
resolvers += "opendatagroup maven" at "http://repository.opendatagroup.com/maven"

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings = commonSettings ++ gitSettings ++ scalafmtSettings

lazy val commonSettings =
  Seq(
    scalaVersion := "2.11.12",
    organization in ThisBuild := "ch.chuv.lren.woken",
    organizationName in ThisBuild := "LREN CHUV for Human Brain Project",
    homepage in ThisBuild := Some(url(s"https://github.com/HBPMedical/${name.value}/#readme")),
    licenses in ThisBuild := Seq("AGPL-3.0" ->
      url(s"https://github.com/LREN-CHUV/${name.value}/blob/${version.value}/LICENSE")),
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

// Create a new MergeStrategy for aop.xml files
val aopMerge: MergeStrategy = new MergeStrategy {
  val name = "aopMerge"
  import scala.xml._
  import scala.xml.dtd._

  def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
    val dt = DocType("aspectj", PublicID("-//AspectJ//DTD//EN", "http://www.eclipse.org/aspectj/dtd/aspectj.dtd"), Nil)
    val file = MergeStrategy.createMergeTarget(tempDir, path)
    val xmls: Seq[Elem] = files.map(XML.loadFile)
    val aspectsChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "aspects" \ "_")
    val weaverChildren: Seq[Node] = xmls.flatMap(_ \\ "aspectj" \ "weaver" \ "_")
    val options: String = xmls.map(x => (x \\ "aspectj" \ "weaver" \ "@options").text).mkString(" ").trim
    val weaverAttr = if (options.isEmpty) Null else new UnprefixedAttribute("options", options, Null)
    val aspects = new Elem(null, "aspects", Null, TopScope, false, aspectsChildren: _*)
    val weaver = new Elem(null, "weaver", weaverAttr, TopScope, false, weaverChildren: _*)
    val aspectj = new Elem(null, "aspectj", Null, TopScope, false, aspects, weaver)
    XML.save(file.toString, aspectj, "UTF-8", xmlDecl = false, dt)
    IO.append(file, IO.Newline.getBytes(IO.defaultCharset))
    Right(Seq(file -> path))
  }
}

// Use defaultMergeStrategy with a case for aop.xml
// I like this better than the inline version mentioned in assembly's README
val customMergeStrategy: String => MergeStrategy = {
  case PathList("META-INF", "aop.xml") =>
    aopMerge
  case s =>
    MergeStrategy.defaultMergeStrategy(s)
}
