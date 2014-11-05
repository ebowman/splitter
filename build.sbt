import AssemblyKeys._

assemblySettings

name := "splitter"

organization := "com.tomtom"

version := "0.14-SNAPSHOT"

scalaVersion := "2.11.2"

mainClass in assembly := Some("tomtom.splitter.layer7.Proxy")

libraryDependencies ++= Seq(
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2",
    "commons-pool" % "commons-pool" % "1.5.5" % "compile->default" withSources () withJavadoc (),
    "ch.qos.logback" % "logback-classic" % "0.9.28" % "compile->default" withSources () withJavadoc (),
    "ch.qos.logback" % "logback-core" % "0.9.28" % "compile->default" withSources () withJavadoc (),
    "org.jboss.netty" % "netty" % "3.2.7.Final" % "compile->default" withSources (),
    "org.mongodb" %% "casbah" % "2.7.3",
    "org.scalatest" %% "scalatest" % "2.2.1" % "test" withSources() withJavadoc (),
    "junit" % "junit" % "4.8.2" % "test->default" withSources () withJavadoc ()
)


