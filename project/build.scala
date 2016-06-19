import sbt._
import Keys._
import complete._
import complete.DefaultParsers._

object BuildSettings extends Build {

  override lazy val settings = super.settings ++ Seq(
    organization := "berkeley",
    version      := "1.2",
    scalaVersion := "2.11.7",
    parallelExecution in Global := false,
    traceLevel   := 15,
    scalacOptions ++= Seq("-deprecation","-unchecked"),
    libraryDependencies ++= Seq("org.scala-lang" % "scala-reflect" % scalaVersion.value)
  )

  lazy val template = Project(
    id = "rocket-to-x",
    base = file("template"),
    settings = settings
  )

  lazy val chisel = project in file("chisel" + sys.env.getOrElse("CHISEL_VERSION", 2))
  lazy val cde        = project in file("context-dependent-environments")
  lazy val hardfloat  = project.dependsOn(chisel)
  lazy val junctions  = project.dependsOn(chisel, cde)
  lazy val uncore     = project.dependsOn(junctions)
  lazy val rocket     = project.dependsOn(template,hardfloat, uncore)
  lazy val groundtest = project.dependsOn(rocket)
  lazy val rocketchip = (project in file(".")).settings(chipSettings).dependsOn(groundtest)

  lazy val addons = settingKey[Seq[String]]("list of addons used for this build")
  lazy val make = inputKey[Unit]("trigger backend-specific makefile command")
  val setMake = NotSpace ~ ( Space ~> NotSpace )

  val chipSettings = Seq(
    addons := {
      val a = sys.env.getOrElse("ROCKETCHIP_ADDONS", "")
      println(s"Using addons: $a")
      a.split(" ")
    },
    unmanagedSourceDirectories in Compile ++= addons.value.map(baseDirectory.value / _ / "src/main/scala"),
    mainClass in (Compile, run) := Some("rocketchip.TestGenerator"),
    make := {
      val jobs = java.lang.Runtime.getRuntime.availableProcessors
      val (makeDir, target) = setMake.parsed
      (run in Compile).evaluated
      s"make -C $makeDir  -j $jobs $target" !
    }
  )
}
