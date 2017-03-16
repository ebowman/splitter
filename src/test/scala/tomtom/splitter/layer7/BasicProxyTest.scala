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

import java.io.File
import java.util.concurrent.{ExecutorService, Executors, Semaphore}

import org.jboss.netty.handler.codec.http.{HttpRequest, HttpResponse, HttpResponseStatus, HttpVersion}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import org.slf4j.LoggerFactory
import tomtom.splitter.config.Config
import tomtom.splitter.layer7.DataType._
import tomtom.splitter.layer7.FixtureConfig._
import tomtom.splitter.layer7.PortFactory._
import tomtom.splitter.layer7.SourceType._

object MutexHelper {

  implicit class MutexOptionWrapper(val optMutex: Option[Semaphore]) extends AnyVal {
    def acquire(): Unit = optMutex.foreach(_.acquire())

    def release(): Unit = optMutex.foreach(_.release())
  }

}

/**
  * Document me.
  *
  * @author Eric Bowman
  * @since 2011-04-07 09:19
  */
//noinspection TypeAnnotation
class BasicProxyTest extends WordSpec with Matchers with BeforeAndAfterEach {

  import MutexHelper._

  // bring up a reference server that can accept commands to either
  // respond normally, respond slowly, or return an error
  implicit val executor: ExecutorService = Executors.newCachedThreadPool
  val log = LoggerFactory.getLogger(getClass)
  val proxyPort = findPort()
  val referencePort = findPort()
  val shadowPort = findPort()
  val referenceServer = new CommandableServer("reference", referencePort)
  val shadowServer = new CommandableServer("shadow", shadowPort)
  var proxyConfig: FixtureConfig = _
  @volatile var mutex = None: Option[Semaphore]
  var _dataSunk: List[FixtureSink] = _

  def notifier(testSink: FixtureSink) {
    this synchronized {
      _dataSunk ::= testSink
    }
    mutex.release()
  }

  def dataSunk = this synchronized {
    _dataSunk
  }

  Config.loadFile(new File("src/test/resources/test.config"))
  Config.config.configOpt("audit").foreach(Logging.config)

  override def beforeEach() {
    referenceServer.start()
    shadowServer.start()
    proxyConfig = FixtureConfig(proxyPort, referencePort, shadowPort, notifier)
    proxyConfig.start()
    this synchronized {
      _dataSunk = Nil
    }
  }

  override def afterEach() {
    referenceServer.stop()
    shadowServer.stop()
    proxyConfig.stop()
  }

  "The servers" should {

    "say hello" in {
      val referenceClient = new HttpClient("localhost", referencePort) << (
        "/?reference=ok&test=say+hello", {
        case (r, s) => assert(s === "reference ok")
      })

      referenceClient.close()
      referenceClient.assertOk()
    }

    "pause reasonably " in {
      val start = System.currentTimeMillis
      val client = HttpClient(port = referencePort) <<
        ("/?reference=sleep+1000&test=pause+reasonably", {
          case (_, s) => assert(s === "reference sleep 1000")
        })
      client.close()
      client.assertOk()
      assert(System.currentTimeMillis - start >= 1000)
    }

    "error out on command" in {
      val client = HttpClient(port = referencePort) <<
        ("/?reference=status+500&test=error+out+on+command", {
          case (r, _) => assert(r.getStatus === HttpResponseStatus.INTERNAL_SERVER_ERROR)
        })
      client.close()
      client.assertOk()
    }
    "proxy a basic request" in {
      mutex = Some(new Semaphore(1))
      mutex.acquire()
      val client = HttpClient(port = proxyPort) <<
        ("/?reference=ok&shadow=ok&test=proxy+a+basic+request", {
          case (r, s) => assert(s === "reference ok")
        })
      client.close()
      client.assertOk()
      mutex.acquire()
      assert(dataSunk.size === 1)
      val testSink = dataSunk.head
      assert(HttpClient.cb2String(testSink.messages(Reference, Response).getContent) === "reference ok")
      assert(HttpClient.cb2String(testSink.messages(Shadow, Response).getContent) === "shadow ok")
    }

    "be immune to a shadow error" in {
      mutex = Some(new Semaphore(1))
      mutex.acquire()
      val client = HttpClient(port = proxyPort) <<
        ("/?reference=ok&shadow=status+500&test=be+immune+to+a+shadow+error", {
          case (r, s) => assert(s === "reference ok")
        })
      client.close()
      client.assertOk()
      mutex.acquire()
      assert(dataSunk.size === 1)
      val testSink = dataSunk.head
      assert(HttpClient.cb2String(testSink.messages(Reference, Response).getContent) === "reference ok")
      assert(HttpClient.cb2String(testSink.messages(Shadow, Response).getContent) === "shadow status 500")
    }

    "be immune to a slow shadow" in {
      mutex = Some(new Semaphore(1))
      mutex.acquire()
      val start = System.currentTimeMillis
      val client = HttpClient(port = proxyPort) <<
        ("/?reference=ok&shadow=sleep+1000&test=be+immune+to+a+slow+shadow", {
          case (r, s) => assert(s === "reference ok")
        })
      client.close()
      client.assertOk()
      val stop = System.currentTimeMillis
      mutex.acquire()
      assert(dataSunk.size === 1)
      assert(stop - start < 500, "Should have been < 500, was " + (stop - start))
      val testSink = dataSunk.head
      assert(HttpClient.cb2String(testSink.messages(Reference, Response).getContent) === "reference ok")
      assert(HttpClient.cb2String(testSink.messages(Shadow, Response).getContent) === "shadow sleep 1000")
    }

    "be ok with a succession of http/1.1 requests on the same socket" in {
      mutex = Some(new Semaphore(2))
      mutex.acquire()
      mutex.acquire()
      val client = HttpClient(port = proxyPort) <<
        ("/?reference=ok&shadow=ok&test=success+http+1.1+1", {
          case (r, s) => assert(s === "reference ok")
        }) <<
        ("/?reference=ok&shadow=ok&test=success+http+1.1+1", {
          case (r, s) => assert(s === "reference ok")
        })

      client.close()
      client.assertOk()
      mutex.acquire()
      mutex.acquire()
      assert(dataSunk.size === 2)
      log.info("dataSunk = {}", dataSunk)
      var header = 2
      for (testSink <- dataSunk) {
        assert(HttpClient.cb2String(testSink.messages(Reference, Response).getContent) === "reference ok")
        assert(HttpClient.cb2String(testSink.messages(Shadow, Response).getContent) === "shadow ok")
        assert(testSink.messages(Reference, Response).headers.get("X-Request-Id") == header.toString)
        assert(testSink.messages(Shadow, Response).headers.get("X-Request-Id") == header.toString)
        header -= 1
      }
    }

    "be ok with a succession of http/1.0 requests" in {
      mutex = Some(new Semaphore(2))
      mutex.acquire()
      mutex.acquire()
      val client = new HttpClient(port = proxyPort) {
        override def supplementRequest(httpRequest: HttpRequest): HttpRequest = {
          httpRequest.setProtocolVersion(HttpVersion.HTTP_1_0)
          httpRequest
        }
      } <<
        ("/?reference=ok&shadow=ok&test=success+http+1.0+1", {
          case (r, s) => assert(s === "reference ok", "reference +1 was not ok")
        }) <<
        ("/?reference=ok&shadow=ok&test=success+http+1.0+2", {
          case (r, s) => assert(s === "reference ok", "reference +2 was not ok")
        })

      client.close()
      client.assertOk()
      mutex.acquire()
      mutex.acquire()
      assert(dataSunk.size === 2)
      var header = 2
      for (testSink <- dataSunk) {
        assert(HttpClient.cb2String(testSink.messages(Reference, Response).getContent) === "reference ok", "Did not find 'reference ok'")
        assert(HttpClient.cb2String(testSink.messages(Shadow, Response).getContent) === "shadow ok", "Did not find 'shadow ok'")
        assert(testSink.messages(Reference, Response).headers.get("X-Request-Id") == header.toString, "Did not find reference 'X-Request-Id'")
        assert(testSink.messages(Shadow, Response).headers.get("X-Request-Id") == header.toString, "Did not find shadow 'X-Request-Id'")
        header -= 1
      }
    }

    "be ok if the shadow refuses connections" in {
      proxyConfig.stop()
      proxyConfig = FixtureConfig(proxyPort, referencePort, ProxiedServer("localhost:1"), notifier)
      proxyConfig.start()
      mutex = Some(new Semaphore(1))
      mutex.acquire()
      val client = HttpClient(port = proxyPort) <<
        ("/?reference=ok&shadow=ok&test=shadow+refuses", {
          case (r, s) => assert(s === "reference ok")
        })
      client.close()
      client.assertOk()
      mutex.acquire()
      log.info("dataSunk = {}", dataSunk)
      assert(dataSunk.size === 1)
      val testSink = dataSunk.head
      assert(HttpClient.cb2String(testSink.messages(Reference, Response).getContent) === "reference ok")
      assert(testSink.messages(Shadow, Response).asInstanceOf[HttpResponse].getStatus === HttpResponseStatus.GATEWAY_TIMEOUT)
    }

    "be ok if the shadow is firewalled" in {
      proxyConfig.stop()
      proxyConfig = FixtureConfig(proxyPort, referencePort, ProxiedServer("google.com:44"), notifier)
      proxyConfig.start()
      mutex = Some(new Semaphore(1))
      mutex.acquire()
      val start = System.currentTimeMillis
      val client = HttpClient(port = proxyPort) <<
        ("/?reference=ok&shadow=ok&test=shadow+firewalled", {
          case (r, s) => assert(s === "reference ok")
        })
      client.close()
      client.assertOk()
      val end = System.currentTimeMillis
      assert((end - start) < 100, "Should have been < 100, was " + (end - start))
      mutex.acquire()
      assert(dataSunk.size === 1)
      val testSink = dataSunk.head
      assert(HttpClient.cb2String(testSink.messages(Reference, Response).getContent) === "reference ok")
      assert(testSink.messages(Shadow, Response).asInstanceOf[HttpResponse].getStatus === HttpResponseStatus.GATEWAY_TIMEOUT)
    }
  }
  type ? = this.type
}
