/*
 * Copyright (c) 2011 by TomTom International B.V. All rights reserved.
 */

import sbt._

class SplitterProject(info: ProjectInfo) extends DefaultProject(info) {

  // override def mainClass = Some("tomtom.splitter.layer7.Proxy")

  val mavenLocal = "Local Maven Repository" at "file://" + Path.userHome + "/.m2/repository"
  val scalaReleases = "scala releases" at "http://scala-tools.org/repo-releases"
  val jboss = "jboss releases" at "http://repository.jboss.org/nexus/content/groups/public"

  override def libraryDependencies = Set(
    "commons-pool" % "commons-pool" % "1.5.5" % "compile->default" withSources () withJavadoc (),
    "ch.qos.logback" % "logback-classic" % "0.9.28" % "compile->default" withSources () withJavadoc (),
    "ch.qos.logback" % "logback-core" % "0.9.28" % "compile->default" withSources () withJavadoc (),
    "org.jboss.netty" % "netty" % "3.2.4.Final" % "compile->default" withSources (),
    "com.mongodb.casbah" %% "casbah" % "2.1.5-1",
    "org.scalatest" % "scalatest_2.9.0" % "1.6.1" % "test" withSources() withJavadoc (),
    "junit" % "junit" % "4.8.2" % "test->default" withSources () withJavadoc ()
  ) ++ super.libraryDependencies

  override def compileOptions = super.compileOptions ++ compileOptions("-unchecked")
}

// vim: set ts=4 sw=4 et:
