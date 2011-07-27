name := "splitter"

organization := "com.tomtom"

version := "0.14-SNAPSHOT"

scalaVersion := "2.9.0-1"

libraryDependencies ++= Seq(
    "commons-pool" % "commons-pool" % "1.5.5" % "compile->default" withSources () withJavadoc (),
    "ch.qos.logback" % "logback-classic" % "0.9.28" % "compile->default" withSources () withJavadoc (),
    "ch.qos.logback" % "logback-core" % "0.9.28" % "compile->default" withSources () withJavadoc (),
    "org.jboss.netty" % "netty" % "3.2.4.Final" % "compile->default" withSources (),
    "com.mongodb.casbah" %% "casbah" % "2.1.5-1",
    "org.scalatest" % "scalatest_2.9.0" % "1.6.1" % "test" withSources() withJavadoc (),
    "junit" % "junit" % "4.8.2" % "test->default" withSources () withJavadoc ()
)

