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

import java.util.concurrent.Executors
import org.jboss.netty.channel.socket.nio.{NioClientSocketChannelFactory, NioServerSocketChannelFactory}
import java.util.UUID
import org.slf4j.LoggerFactory
import org.jboss.netty.handler.codec.http.{HttpResponse, HttpRequest, HttpChunk, HttpMessage}
import java.io.File

/**
 * Document me.
 *
 * @author Eric Bowman
 * @since 2011-04-06 21:01
 */

object FixtureSink {

  import SourceType._, DataType._

  val mustExist: Set[(SourceType, DataType)] =
    Set((Reference, Request), (Reference, Response), (Shadow, Response), (Shadow, Request))
  val log = LoggerFactory.getLogger(classOf[FixtureConfig])
  val refKey = (Reference, Response)
  val shadKey = (Shadow, Response)
}

class FixtureSink(notifier: (FixtureSink => Unit)) extends DataSink {

  @volatile var messages = Map[(SourceType.SourceType, DataType.DataType), HttpMessage]()
  @volatile var chunks = Map[(SourceType.SourceType, DataType.DataType), List[HttpChunk]]()

  import SourceType._, DataType._

  def append(sourceType: SourceType, dataType: DataType, chunk: HttpChunk) {
    this synchronized {
      val key = (sourceType, dataType)
      val c = chunks.getOrElse(key, Nil)
      chunks += (key -> (chunk :: c))
      if (chunk.isLast) {
        tryNotify()
      }
    }
  }

  def append(sourceType: SourceType, dataType: DataType, message: HttpMessage) {
    this synchronized {
      require(messages.get((sourceType, dataType)) == None)
      messages += ((sourceType, dataType) -> message)
      tryNotify()
    }
  }

  def tryNotify() {
    import FixtureSink._
    val doNotify = if (messages.keySet.intersect(mustExist) == mustExist) {
      mustExist find {
        t =>
          messages(t).isChunked && (!chunks.contains(t) || !chunks(t).head.isLast)
      } match {
        case Some(_) => false
        case None => true
      }
    } else {
      false
    }
    if (doNotify && notifier != null) {
      // no other messages should contain this request id!
      val requestId = messages(refKey).getHeader("X-Request-Id").toInt
      val shadId = messages(refKey).getHeader("X-Request-Id").toInt
      require(requestId == shadId)
      notifier(this)
    }
  }

  override def toString = {
    messages.toString + "\n" + chunks.toString
  }

  def sinkResponseChunk(sourceType: SourceType.SourceType, chunk: HttpChunk) = append(sourceType, Response, chunk)

  def sinkRequestChunk(sourceType: SourceType.SourceType, chunk: HttpChunk) = append(sourceType, Request, chunk)

  def sinkResponse(sourceType: SourceType.SourceType, message: HttpResponse) = append(sourceType, Response, message)

  def sinkRequest(sourceType: SourceType.SourceType, message: HttpRequest) = append(sourceType, Request, message)
}

object FixtureConfig {
  implicit def intToProxiedServer(port: Int) = ProxiedServer("localhost:" + port)
}

case class FixtureConfig(port: Int,
                         reference: ProxiedServer,
                         shadow: ProxiedServer,
                         notifier: (FixtureSink => Unit) = null) extends ProxyConfig {

  val executor = Executors.newCachedThreadPool
  val inboundSocketFactory = new NioServerSocketChannelFactory(executor, executor)
  val inboundBootstrap = new InboundBootstrap
  val poolConfig = PoolConfig(connectTimeoutMillis = 5000, numTestsPerEvictionRuns = 0)
  val enableShadowing = true

  val outboundChannelFactory = new NioClientSocketChannelFactory(executor, executor)
  val connectionFactory = new ConnectionPoolFactory
  val connectionPool = new ConnectionPoolImpl
  val sessionId = UUID.randomUUID.toString
  val mongoConfig = null
  val dataSinkFactory = new DataSinkFactory {
    def dataSink(id: Int) = {
      new FixtureSink(notifier)
    }
  }
  val rewriteShadowUrl = identity _
  val referenceHostname = None: Option[String]
  val shadowHostname = None: Option[String]

  val rewriteConfig = Some(new File("ofbiz.config"))
}
