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
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterEach, WordSpec}
import java.util.concurrent.{Semaphore, Executors, ExecutorService}
import FixtureConfig._
import org.slf4j.LoggerFactory

import SourceType._, DataType._
import org.jboss.netty.handler.codec.http.{HttpHeaders, HttpResponse, HttpRequest, HttpResponseStatus}
import tomtom.splitter.config.Config
import java.io.File

// implicit int-to-ProxiedServer

/**
 * Confirms that some well-known BA URLs are rewritten properly for Ofbiz
 */
@RunWith(classOf[JUnitRunner])
class OfbizShadowTest extends WordSpec with ShouldMatchers with BeforeAndAfterEach {

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
  @volatile var mutex = None: Option[Semaphore]
  var _dataSunk: List[FixtureSink] = null

  def notifier(testSink: FixtureSink) {
    this synchronized {
      _dataSunk ::= testSink
    }
    mutex.map(_.release())
  }

  def dataSunk = this synchronized {
    _dataSunk
  }

  Config.loadFile(new File("src/test/resources/test.config"))
  Config.config.configOpt("audit").foreach(Logging.config(_))

  override def beforeEach() {
    referenceServer.start()
    shadowServer.start()
    proxyConfig = new FixtureConfig(proxyPort, referencePort, shadowPort, notifier) {
      override val rewriteShadowUrl = RequestMapper.rewrite _
      override val referenceHostname = None // Some("ba.tomtom.com")
      override val shadowHostname = Some("ofbiz.tomtom.com")

    }
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

  def refReq = dataSunk.head.messages(Reference, Request).asInstanceOf[HttpRequest]

  def refResp = dataSunk.head.messages(Reference, Response).asInstanceOf[HttpResponse]

  def shadReq = dataSunk.head.messages(Shadow, Request).asInstanceOf[HttpRequest]

  def shadResp = dataSunk.head.messages(Shadow, Response).asInstanceOf[HttpResponse]

  def refContent = HttpClient.cb2String(dataSunk.head.messages(Reference, Response).getContent)

  def shadContent = HttpClient.cb2String(dataSunk.head.messages(Shadow, Response).getContent)

  "The ofbiz-configured proxy" should {

    "rewrite a CreateUser user POST" in {

      mutex = Some(new Semaphore(1))
      mutex.foreach(_.acquire())
      val referenceClient = new HttpClient("localhost", proxyPort) POST (
        "/buenos-aires-ws/services/wfe/users/?reference=host&shadow=host", {
        case (r, s) =>
      })

      referenceClient.close()
      referenceClient.assertOk()
      mutex.foreach(_.acquire())

      assert(refReq.getHeader(HttpHeaders.Names.HOST) === "localhost:" + proxyPort)
      assert(shadReq.getHeader(HttpHeaders.Names.HOST) === "ofbiz.tomtom.com")
      assert(refContent === "HOST=localhost:" + proxyPort)
      assert(shadContent === "HOST=ofbiz.tomtom.com")
      assert(refReq.getUri === "/buenos-aires-ws/services/wfe/users/?reference=host&shadow=host")
      assert(shadReq.getUri === "/ttuser/atom/users/?reference=host&shadow=host")
    }
  }

  "not rewrite a CreateUser user GET" in {

    mutex = Some(new Semaphore(1))
    mutex.foreach(_.acquire())
    val referenceClient = new HttpClient("localhost", proxyPort) GET (
      "/buenos-aires-ws/services/wfe/users/?reference=host&shadow=host", {
      case (r, s) =>
    })

    referenceClient.close()
    referenceClient.assertOk()
    mutex.foreach(_.acquire())

    assert(refReq.getHeader(HttpHeaders.Names.HOST) === "localhost:" + proxyPort)
    assert(shadReq.getHeader(HttpHeaders.Names.HOST) === refReq.getHeader(HttpHeaders.Names.HOST))
    assert(refContent === "HOST=localhost:" + proxyPort)
    assert(shadContent === "")
    assert(refReq.getUri === "/buenos-aires-ws/services/wfe/users/?reference=host&shadow=host")
    assert(shadReq.getUri === refReq.getUri)
    assert(refResp.getStatus === HttpResponseStatus.OK)
    assert(shadResp.getStatus === HttpResponseStatus.PRECONDITION_FAILED)
  }
  type ? = this.type
}
