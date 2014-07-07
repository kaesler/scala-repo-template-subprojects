import sbt._
import sbt.Keys._
import com.typesafe.sbteclipse.plugin.EclipsePlugin
import com.typesafe.sbteclipse.plugin.EclipsePlugin.EclipseKeys
import sbtassembly.Plugin._
import AssemblyKeys._
import sbt.Package.ManifestAttributes
import java.io._
import java.util.jar.Attributes
import de.johoop.jacoco4sbt.JacocoPlugin._
import sbtunidoc.Plugin._
import UnidocKeys._
import scala.xml._
import scala.xml.transform._

object FooBuild extends Build {
  import BuildSettings._
  import Dependencies._

  val appName = "foo"

  // Configure prompt to show current project
  override lazy val settings = super.settings :+ {
    shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Root Project
  // -------------------------------------------------------------------------------------------------------------------

  lazy val root = Project("root",file("."))
    .aggregate(foo, bar)
    .settings(basicSettings: _*)
    .settings(unidocSettings: _*)

  // -------------------------------------------------------------------------------------------------------------------
  // Sub projects
  // -------------------------------------------------------------------------------------------------------------------

  lazy val foo = Project(appName, file(appName))
    .dependsOn(bar % "test->test;compile->compile")
    .settings(basicSettings: _*)
    .settings(assemblySettings: _*)
    .settings(jacoco.settings: _*)
    .settings(net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)
    .settings(
      // This is supposed to add resource directories as source members of classpath.
      EclipseKeys.createSrc := EclipsePlugin.EclipseCreateSrc.Default + EclipsePlugin.EclipseCreateSrc.Resource,
      EclipseKeys.withSource := true
    )
    .settings(
      scalaVersion          := "2.11.1",
      resolvers ++= Dependencies.resolutionRepos,
      credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
      autoCompilerPlugins := true,
      libraryDependencies ++= (
        compile(
          akkaActor,
          akkaCluster,
          akkaContrib,
          akkaSlf4j,
          config,
          logback,
          metricsScala,
          metricsJson,
          nscalaTime,
          scalaStm,
          scalactic,
          sprayCan,
          sprayClient,
          sprayRouting,
          sprayHttpx,
          sprayJson
        ) ++
        runtime(
          akkaSlf4j,
          logback
        ) ++
        testing(
          akkaActor,
          akkaCluster,
          akkaTestkit,
          scalacheck,
          scalatest,
          sprayCan,
          sprayTestkit
        )
      )
    )

  lazy val bar = Project("bar", file("bar"))
    .settings(basicSettings: _*)
    .settings(assemblySettings: _*)
    .settings(jacoco.settings: _*)
    .settings(net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)
    .settings(
      // This is supposed to add resource directories as source members of classpath.
      EclipseKeys.createSrc := EclipsePlugin.EclipseCreateSrc.Default + EclipsePlugin.EclipseCreateSrc.Resource,
      EclipseKeys.withSource := true
    )
    .settings(
      scalaVersion          := "2.11.1",
      resolvers ++= Dependencies.resolutionRepos,
      credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
      autoCompilerPlugins := true,
      libraryDependencies ++= (
        compile(
          akkaActor,
          akkaCluster,
          akkaContrib,
          akkaSlf4j,
          config,
          logback,
          metricsScala,
          metricsJson,
          nscalaTime,
          scalaStm,
          scalactic,
          sprayCan,
          sprayClient,
          sprayRouting,
          sprayHttpx,
          sprayJson
        ) ++
        runtime(
          akkaSlf4j,
          logback
        ) ++
        testing(
          akkaActor,
          akkaCluster,
          akkaTestkit,
          scalacheck,
          scalatest,
          sprayCan,
          sprayTestkit
        )
      )
    )
}

object BuildSettings {

  lazy val basicSettings = seq(
    version               := versionWithBuildNumber(baseVersion),
    homepage              := Some(new URL("http://timetrade.com")),
    organization          := "TimeTrade System, Inc.",
    organizationHomepage  := Some(new URL("http://timetrade.com")),
    description           := "Foo",
    startYear             := Some(2014),
    scalaVersion          := "2.11.1",
    resolvers             ++= Dependencies.resolutionRepos,
    scalacOptions         := Seq("-Xlint", "-feature", "-deprecation", "-encoding", "utf8"),
    fork in Test          := true,
    javaOptions in Test   ++= Seq("-Xmx4G", "-XX:-HeapDumpOnOutOfMemoryError"),
    parallelExecution in jacoco.Config := false
  )

  val baseVersion = "1.0.0"

  val specVersion = { version: String =>
    sys.env.get("BUILD_NUMBER") match {
      case None => "working"
      case buildNum => version
    }
  }

  val implVersion =
    sys.env.get("BUILD_NUMBER") match {
      case None => "0"
      case buildNum => buildNum.get
    }

  val versionWithBuildNumber = { version: String =>
    specVersion(version) + "." + implVersion
  }
}

object Dependencies {

  val resolutionRepos = Seq(

    Resolver.url("Timetrade Nexus repo",
                  url("https://devart.timetradesystems.com/nexus/content/repositories/releases"))
                  (Patterns(Seq("[organisation]/[module]/[revision]/ivy-[revision].xml"),
                            Seq("[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier]).[ext]"),
                            true)),

    "spray repo"      at "http://repo.spray.io/",

    "Typesafe Releases" at "http://repo.typesafe.com/typesafe/maven-releases/"

    // "spray nightlies repo"  at  "http://nightlies.spray.io/"
    //"Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
  )

  def compile   (deps: ModuleID*): Seq[ModuleID] = deps map (_ % "compile")
  def provided  (deps: ModuleID*): Seq[ModuleID] = deps map (_ % "provided")
  def testing   (deps: ModuleID*): Seq[ModuleID] = deps map (_ % "test")
  def runtime   (deps: ModuleID*): Seq[ModuleID] = deps map (_ % "runtime")
  def container (deps: ModuleID*): Seq[ModuleID] = deps map (_ % "container")

  val akkaActor        = ("com.typesafe.akka"             %% "akka-actor"              % "2.3.4" withSources())
  val akkaCluster      = ("com.typesafe.akka"             %% "akka-cluster"            % "2.3.4" withSources())
  val akkaContrib      = ("com.typesafe.akka"             %% "akka-contrib"            % "2.3.4" withSources())
  val akkaSlf4j        = ("com.typesafe.akka"             %% "akka-slf4j"              % "2.3.4" withSources())
  val akkaTestkit      = ("com.typesafe.akka"             %% "akka-testkit"            % "2.3.4" withSources())
  val config           = ("com.typesafe"                  % "config"                       % "1.2.1" withSources())
  val logback          = ("ch.qos.logback"                % "logback-classic"              % "1.0.13" withSources())
  val metricsScala     = ("nl.grons"                      %% "metrics-scala"               % "3.2.0" withSources())
  val metricsJson      = ("com.codahale.metrics"          % "metrics-json"                 % "3.0.2" withSources())
      // Was necessary to avoid getting an incompatible akka-actor-2.3-M1 jar
      .exclude( "com.typesafe.akka",                   "akka-actor_2.10"   )
  val nscalaTime       = ("com.github.nscala-time"        %% "nscala-time"                 % "1.2.0" withSources())
  val scalacheck       = ("org.scalacheck"                %% "scalacheck"                  % "1.11.4" withSources())
  val scalaStm         = ("org.scala-stm"                 %% "scala-stm"                   % "0.7"  withSources())
  val scalatest        = ("org.scalatest"                 %% "scalatest"                   % "2.2.0" withSources())
  val scalactic        = ("org.scalactic"                 %% "scalactic"                   % "2.2.0" withSources())
  val slf4j            = ("org.slf4j"                     % "slf4j-api"                    % "1.7.5")
  val sprayCan         = ("io.spray"                      %% "spray-can"                   % "1.3.1" withSources())
  val sprayClient      = ("io.spray"                      %% "spray-client"                % "1.3.1" withSources())
  val sprayRouting     = ("io.spray"                      %% "spray-routing"               % "1.3.1" withSources())
  val sprayHttpx       = ("io.spray"                      %% "spray-httpx"                 % "1.3.1" withSources())
  val sprayJson        = ("io.spray"                      %% "spray-json"                  % "1.2.6" withSources())
  val sprayTestkit     = ("io.spray"                      %% "spray-testkit"               % "1.3.1" withSources())
}
