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
          library.sprayCan,
          library.sprayJson,
          library.sprayRouting,
          library.slf4j,
          library.scalaz,
          library.config,
          library.doobieCore,
          library.doobiePostgres,
          library.yaml,
          library.hadrian,
          library.wokenMessages,
          library.spraySwagger,
          library.swaggerUI,
          library.scalaCheck   % Test,
          library.scalaTest    % Test,
          library.akkaTestkit  % Test
        )
      )
    )

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {
    object Version {
      val scalaCheck    = "1.13.5"
      val scalaTest     = "3.0.3"
      val akka          = "2.3.16"
      val spray         = "1.3.4"
      val sprayJson     = "1.3.4"
      val sprayRouting  = "1.3.3"
      val slf4j         = "1.7.13"
      val scalaz        = "7.2.7"
      val config        = "1.2.1"
      val doobie        = "0.4.0"
      val snakeyaml     = "1.17"
      val hadrian       = "0.8.5"
      val wokenMessages = "2.0.1"
      val spraySwagger  = "0.5.0"
      val swaggerUI     = "2.0.12"
    }
    val scalaCheck: ModuleID   = "org.scalacheck"    %% "scalacheck"   % Version.scalaCheck
    val scalaTest: ModuleID    = "org.scalatest"     %% "scalatest"    % Version.scalaTest
    val akkaActor: ModuleID    = "com.typesafe.akka" %% "akka-actor"   % Version.akka
    val akkaRemote: ModuleID   = "com.typesafe.akka" %% "akka-remote"  % Version.akka
    val akkaCluster: ModuleID  = "com.typesafe.akka" %% "akka-cluster" % Version.akka
    val akkaTestkit: ModuleID  = "com.typesafe.akka" %% "akka-testkit" % Version.akka
    val sprayCan: ModuleID     = "io.spray"          %% "spray-can"    % Version.spray exclude("io.spray", "spray-routing")
    val sprayRouting: ModuleID = "io.spray"          %% "spray-routing-shapeless2" % Version.sprayRouting
    val sprayJson: ModuleID    = "io.spray"          %% "spray-json"   % Version.sprayJson
    val slf4j: ModuleID        = "org.slf4j"          % "slf4j-api"    % Version.slf4j
    val scalaz: ModuleID       = "org.scalaz"        %% "scalaz-core"  % Version.scalaz
    val config: ModuleID       = "com.typesafe"       % "config"       % Version.config
    val doobieCore: ModuleID   = "org.tpolecat"      %% "doobie-core"  % Version.doobie
    val doobiePostgres: ModuleID = "org.tpolecat"    %% "doobie-postgres" % Version.doobie
    val yaml: ModuleID         = "org.yaml"           % "snakeyaml"    % Version.snakeyaml
    val hadrian: ModuleID      = "com.opendatagroup" %  "hadrian"       % Version.hadrian
    val spraySwagger: ModuleID = "com.gettyimages"   %% "spray-swagger" % Version.spraySwagger excludeAll ExclusionRule(organization = "io.spray")
    val swaggerUI: ModuleID    = "org.webjars"        % "swagger-ui"   % Version.swaggerUI
    val wokenMessages: ModuleID = "eu.humanbrainproject.mip" %% "woken-messages" % Version.wokenMessages
  }

resolvers += "opendatagroup maven" at "http://repository.opendatagroup.com/maven"
resolvers += Resolver.bintrayRepo("hbpmedical", "maven")

// *****************************************************************************
// Settings
// *****************************************************************************

lazy val settings = commonSettings ++ gitSettings ++ scalafmtSettings

lazy val commonSettings =
  Seq(
    scalaVersion := "2.11.8",
    organization := "eu.humanbrainproject.mip",
    organizationName := "LREN CHUV",
    startYear := Some(2017),
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-value-discard",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding",
      "UTF-8"
    ),
    javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
    unmanagedSourceDirectories.in(Compile) := Seq(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Seq(scalaSource.in(Test).value),
    wartremoverWarnings in (Compile, compile) ++= Warts.unsafe,
    mainClass in Runtime := Some("eu.hbp.mip.woken.validation.Main"),
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
    scalafmtVersion := "1.1.0"
  )

/*
val versions = new {
  val spray = "1.3.4"
  val spray_json = "1.3.4"
  val spray_routing = "1.3.3"
  val spark = "2.0.0"
  val scalaz = "7.2.7"
  val slf4j = "1.7.13"
  val config = "1.2.1"
  val doobie = "0.4.0"
  val snakeyaml = "1.17"
  val scalaTest = "2.2.5"
  val spec2 = "3.8.9"
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
    //"org.slf4j"         %%  "slf4j-nop"                % versions.slf4j,
    "org.slf4j"           %   "slf4j-api"                % versions.slf4j,
    //"org.slf4j"         %%  "log4j-over-slf4j"         % versions.slf4j, // For Denodo JDBC driver
    "org.scalaz"          %%  "scalaz-core"              % versions.scalaz,
    "com.typesafe"        %   "config"                   % versions.config,
    "org.tpolecat"        %%  "doobie-core"              % versions.doobie,
    "org.tpolecat"        %%  "doobie-postgres"          % versions.doobie,
    "com.gettyimages"     %%  "spray-swagger"            % "0.5.0" excludeAll ExclusionRule(organization = "io.spray"),
    "org.webjars"         %   "swagger-ui"               % "2.0.12",
    "org.yaml"            %   "snakeyaml"                % versions.snakeyaml,
    "javax.validation"    %   "validation-api"           % "1.1.0.Final",
    "org.glassfish.hk2"   %   "hk2-locator"              % "2.4.0", // Coursier wanted that explicitly
    ("org.apache.spark"   %%  "spark-mllib"              % versions.spark).
      exclude("commons-beanutils", "commons-beanutils-core").
      exclude("commons-collections", "commons-collections").
      exclude("commons-logging", "commons-logging").
      exclude("org.apache.ivy", "ivy"),


  //---------- Test libraries -------------------//
    "org.scalatest"       %%  "scalatest"        % versions.scalaTest % "test",
    "org.specs2"          %%  "specs2-core"      % versions.spec2     % "test",
    "org.tpolecat"        %%  "doobie-specs2"    % versions.doobie    % "test",
    "com.netaporter"      %%  "pre-canned"       % "0.0.7"            % "test",
    "com.typesafe.akka"   %%  "akka-testkit"     % versions.akka      % "test",
    "io.spray"            %%  "spray-testkit"    % versions.spray     % "test"
  )
}

resolvers += "hbpmip artifactory" at "http://hbps1.chuv.ch/artifactory/libs-release/"

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

mainClass in Runtime := Some("eu.hbp.mip.woken.web.Web")

test in assembly := {} // Do not run tests when building the assembly

assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "spark", "unused", xs @ _*) => MergeStrategy.first
  case PathList("org", "apache", "hadoop", "yarn", xs @ _*) => MergeStrategy.first
  case PathList("org", "apache", "hadoop", "yarn", xs @ _*) => MergeStrategy.first
  case PathList("org", "aopalliance", xs @ _*) => MergeStrategy.first
  case PathList("javax", "ws", "rs", xs @ _*) => MergeStrategy.first
  case PathList("javax", "inject", xs @ _*) => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

// TODO Instead of exclude and merge strategy it is safer to use
// Read dependence tree
/*assemblyShadeRules in assembly := Seq(
  ShadeRule.rename("org.apache.commons.io.**" -> "shadeio.@1").inLibrary("commons-io" % "commons-io" % "2.4", ...).inProject
)*/

//net.virtualvoid.sbt.graph.Plugin.graphSettings
*/
