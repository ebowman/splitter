name := "splitter"
organization := "com.tomtom"

version := "0.14-SNAPSHOT"

scalaVersion := "2.12.1"

// see http://typesafe.com/blog/improved-dependency-management-with-sbt-0137
updateOptions := updateOptions.value.withCachedResolution(true)

mainClass in assembly := Some("tomtom.splitter.layer7.Proxy")

scalacOptions ++= Seq("-feature", "-deprecation")

libraryDependencies ++= Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.5",
    "commons-pool" % "commons-pool" % "1.6" % "compile->default",
    "ch.qos.logback" % "logback-classic" % "1.2.1" % "compile->default",
    "ch.qos.logback" % "logback-core" % "1.2.1" % "compile->default",
    "io.netty" % "netty" % "3.10.6.Final" % "compile->default",
    "org.mongodb" %% "casbah" % "3.1.1",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)


