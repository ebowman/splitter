#!/bin/sh
if test -f ~/.sbtconfig; then
  . ~/.sbtconfig
fi
if [ "${SBT_OPTS}" == "" ]; then
    SBT_OPTS="-XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=512m -Xmx1512M"
fi
exec java ${SBT_OPTS} -jar sbt-launch.jar "$@"
