Splitter
========

(c) 2011 TomTom International BV
(c) 2017 Eric Bowman

Introduction
------------

Splitter is a transparent reverse proxy which splits incoming HTTP
requests into two backend requests, one of which goes to a
"reference server", and the other of which goes to a "shadow
server". The response from the reference server is sent back as the
response to the original request, while the response from the shadow
server is discarded. Optionally, the incoming request and both
responses may be logged to a MongoDB server.

Splitter is written in Scala, and uses the JBoss Netty library.

Building
--------

Splitter may be built using sbt:

    ./sbt

    > update
    > test

Configuration
-------------

Configuration is done using a configuration file, similar to those
used by the now-defunct "Configgy" library.


    port = 8080
    reference = "localhost:9090"
    shadow = "localhost:9191"

    enableShadowing = true

    referenceHostname = "reference.test.tomtom.com"
    shadowHostname = "shadow.tomtom.com"

    # "none" | "mongodb"
    capture = "none"

    audit {
       level = "info"
       console = true
       truncate = true
       file = "default"
    }

    pool {
        maxOpenConnections = 70
        maxWaitMs = 5000
        maxIdleConnections = 8
        msBetweenEvictionRuns = 10000
        numTestsPerEvictionRuns = 3
        maxIdleTimeMs = 60000
        connectTimeoutMillis = 30000
        receiveTimeoutMillis = 120000
        keepAlive = true
    }

    mongo {
        host = "localhost"
        port = 27017
        db = "splitter"
    }

Running Splitter
----------------

To run splitter within sbt, you can do:

    > run [path to config file]

There are 3 runnable apps; sbt will ask you which one you want to
run; you should run:

    tomtom.splitter.layer7.Proxy

If you have built a self-contained jar (using 'sbt assembly'), you can run it like:

    java -jar splitter-assembly-0.14-SNAPSHOT.jar [path to config file]

Future Directions
-----------------

This tool is still pretty raw, but already useful.  It might be nice
to support other means besides MongoDB for logging requests.

Also note that it assumes a few ports are free for running the
tests; it would be nice to make this configurable or dynamic.

Credits
-------

The idea for this came from https://github.com/neotyk who made an earlier
version (but on which this version is only based conceptually).

Thanks to TomTom for letting me release this as ASL.

Enjoy!

Eric Bowman
ebowman@boboco.ie


[![Codacy Badge](https://api.codacy.com/project/badge/Grade/67fa05df97494116a1f63b17924d6121)](https://www.codacy.com/app/ebowman/splitter?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ebowman/splitter&amp;utm_campaign=Badge_Grade)

