/*
 * Copyright 2011 TomTom International BV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tomtom.splitter.layer7

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{WordSpec, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.slf4j.LoggerFactory
import java.util.concurrent.{ExecutorService, Executors, Semaphore}
import SourceType._, DataType._
import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponseStatus}
import tomtom.splitter.config.Config
import java.io.File

@RunWith(classOf[JUnitRunner])
class LoadTest extends WordSpec with ShouldMatchers with BeforeAndAfterEach {
  // bring up a reference server that can accept commands to either
  // respond normally, respond slowly, or return an error
  implicit val executor: ExecutorService = Executors.newCachedThreadPool
  val log = LoggerFactory.getLogger(getClass)
  import PortFactory.reservePort

  val proxyPort = reservePort
  val referencePort = reservePort
  val shadowPort = reservePort
  val referenceServer = new CommandableServer("reference", referencePort)
  val shadowServer = new CommandableServer("shadow", shadowPort)
  var proxyConfig: FixtureConfig = _

  val testThreads = 20
  val requests = 10000

  val refKey = (Reference, Response)
  val shadKey = (Shadow, Response)
  val requestKey = (Reference, Request)

  @volatile var mutex = None: Option[Semaphore]

  var _dataSunk: List[FixtureSink] = Nil
  val _seenIds = collection.mutable.Map[Int, HttpRequest]()
  val _dups = collection.mutable.Map[Int, HttpRequest]()

  def notifier(testSink: FixtureSink) {
    val refRequestId = testSink.messages(refKey).getHeader("X-Request-Id")
    val shadRequestId = testSink.messages(shadKey).getHeader("X-Request-Id")
    require(refRequestId == shadRequestId)
    //log.warn("testSink: " + testSink.messages(requestKey).asInstanceOf[HttpRequest].getUri + " -> " + refRequestId)
    val requestId = refRequestId.toInt
    LoadTest.this synchronized {
      _dataSunk ::= testSink
      if (_seenIds.contains(requestId)) {
        log.warn("Already seen {}", requestId)
        _dups += (requestId -> testSink.messages(requestKey).asInstanceOf[HttpRequest])
      } else {
        _seenIds += (requestId -> testSink.messages(requestKey).asInstanceOf[HttpRequest])
      }

    }
    mutex.map(_.release())
  }

  def dataSunk = LoadTest.this synchronized {
    _dataSunk
  }

  Config.loadFile(new File("src/test/resources/test.config"))
  Config.config.configOpt("audit").foreach(Logging.config(_))

  override def beforeEach() {
    referenceServer.start()
    shadowServer.start()
    import FixtureConfig._
    // implicit port-to-ProxiedServer
    proxyConfig = FixtureConfig(proxyPort, referencePort, shadowPort, notifier)
    proxyConfig.start()
    this synchronized {
      _dataSunk = Nil
      _seenIds.clear()
    }
  }

  override def afterEach() {
    referenceServer.stop()
    shadowServer.stop()
    proxyConfig.stop()

    for (id <- _dups.keys) {
      log.warn("--------DUP----------")
      log.warn(_dups(id).toString)
      log.warn("--------ORIG---------")
      log.warn(_seenIds(id).toString)
    }
  }

  "A proxy server under load" should {
    "maintain coherence" in {
      val threadPool = Executors.newFixedThreadPool(testThreads)
      val countdown = (1 to requests).iterator
      mutex = Some(new Semaphore(requests))
      mutex.foreach(_.acquire(requests))
      val clients = for (i <- 1 to testThreads) yield {
        val client = HttpClient(port = proxyPort)
        threadPool.submit(new Runnable {
          override def run() {
            var request = 0
            while (countdown synchronized {
              if (countdown.hasNext) {
                request = countdown.next()
                request match {
                  case x if x % 100 == 0 => // println(x)
                  case _ =>
                }
                true
              } else {
                false
              }
            }) {
              val path = "/request=" + request
              // log.warn("Submitting {}", path)
              client << (path, {
                case (r, _) =>
                  assert(r.getStatus === HttpResponseStatus.OK)
              })
            }
            client.close()
            mutex.foreach(_.release())
          }
        })
        client
      }
      threadPool.shutdown()
      clients foreach {
        _.assertOk()
      }
      // println("Trying to acquire " + requests)
      mutex.foreach(_.acquire(requests))
      // println("Acquired " + requests)
      threadPool.shutdownNow
      dataSunk foreach {
        fixtureSink =>
          fixtureSink.messages.get(refKey) match {
            case Some(response) =>
              val requestId = response.getHeader("X-Request-Id")
              fixtureSink.messages.get(shadKey) match {
                case Some(shadResponse) =>
                  val shadRequestId = shadResponse.getHeader("X-Request-Id")
                  assert(requestId === shadRequestId, "shadResponse = " + shadResponse)
                case None =>
                  log.warn("fixture {} doesn't contain {}", fixtureSink, shadKey)
              }
            case None =>
              log.warn("fixture {} doesn't contain {}", fixtureSink, refKey)
          }
      }
    }
  }
  type ? = this.type
}
