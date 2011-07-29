resolvers += "less is" at "http://repo.lessis.me"

libraryDependencies <+= sbtVersion(v => "me.lessis" %% "sbt-growl-plugin" % "0.1.1-%s".format(v))
