name := "splitter"
organization := "com.tomtom"

version := "0.14-SNAPSHOT"

scalaVersion := "2.11.4"

// see http://typesafe.com/blog/improved-dependency-management-with-sbt-0137
updateOptions := updateOptions.value.withCachedResolution(true)

mainClass in assembly := Some("tomtom.splitter.layer7.Proxy")

libraryDependencies ++= Seq(
    //"com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2",
    "commons-pool" % "commons-pool" % "1.5.7" % "compile->default",
    "ch.qos.logback" % "logback-classic" % "0.9.28" % "compile->default",
    "ch.qos.logback" % "logback-core" % "0.9.28" % "compile->default",
    "io.netty" % "netty" % "3.9.5.Final" % "compile->default",
    "org.jboss.netty" % "netty" % "3.2.7.Final" % "compile->default",
    "org.mongodb" %% "casbah" % "2.7.4",
    "org.scalatest" %% "scalatest" % "2.2.1" % "test",
    "junit" % "junit" % "4.12" % "test->default"
)


