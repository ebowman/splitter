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

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory
import java.util.concurrent.ExecutorService
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import org.jboss.netty.handler.codec.http.{HttpHeaders, HttpChunk, HttpResponse, HttpRequest, HttpClientCodec, DefaultHttpRequest, HttpVersion, HttpMethod}
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.{ChannelStateEvent, ExceptionEvent, Channel, ChannelHandlerContext, MessageEvent, SimpleChannelUpstreamHandler, Channels, ChannelPipelineFactory}
import org.slf4j.LoggerFactory

/**
 * Document me.
 *
 * @author Eric Bowman
 * @since 2011-04-06 14:19
 */

object HttpClient {
  def cb2String(content: ChannelBuffer): String = {
    import content.{array, arrayOffset, readableBytes}
    new String(array, arrayOffset, readableBytes)
  }
}

case class HttpClient(host: String = "localhost", port: Int)(implicit executor: ExecutorService) extends SimpleChannelUpstreamHandler {

  @volatile var onResponses: List[((HttpResponse, String) => Unit)] = Nil
  @volatile var exceptions: List[Throwable] = Nil
  var inFlight = false
  @volatile var isClosed = false
  @volatile var channel: Channel = _
  @volatile var request: HttpRequest = _
  val log = LoggerFactory.getLogger(getClass)

  def supplementRequest(httpRequest: HttpRequest): HttpRequest = httpRequest

  def onChunk(chunk: HttpChunk) {}

  import HttpClient._

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    if (!e.getCause.isInstanceOf[ClosedChannelException]) {
      this synchronized {
        exceptions ::= e.getCause
      }
      e.getCause.printStackTrace()
    } else {
      e.getCause.printStackTrace()
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    isClosed = true
    super.channelClosed(ctx, e)
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    e.getMessage match {
      case response: HttpResponse =>
        this synchronized {
          try {
            onResponses.head(response, cb2String(response.getContent))
          } catch {
            case ex => exceptions ::= ex
          } finally {
            onResponses = onResponses.tail
          }
        }
        if (!response.isChunked) {
          this synchronized {
            if (closed && onResponses == Nil) {
              channel.close()
            }
            inFlight = false
            this.notifyAll()
          }
        }
      case chunk: HttpChunk =>
        onChunk(chunk)
        if (chunk.isLast) {
          this synchronized {
            if (closed && onResponses == Nil) {
              channel.close()
            }
            inFlight = false
            this.notifyAll()
          }
        }
    }
    super.messageReceived(ctx, e)
  }

  val bootstrap = new ClientBootstrap(
    new NioClientSocketChannelFactory(
      executor, executor))

  bootstrap.setPipelineFactory(new ChannelPipelineFactory {
    def getPipeline = {
      val pipeline = Channels.pipeline
      pipeline.addLast("codec", new HttpClientCodec())
      pipeline.addLast("this", HttpClient.this)
      pipeline
    }
  })

  open()


  def open() {
    val connectFuture = bootstrap.connect(new InetSocketAddress(host, port))
    channel = connectFuture.awaitUninterruptibly().getChannel
    if (!connectFuture.isSuccess) {
      connectFuture.getCause.printStackTrace()
      bootstrap.releaseExternalResources()
      sys.error("Could not connect")
    } else {
      isClosed = false
    }
  }


  def POST(path: String, callback: ((HttpResponse, String) => Unit)): HttpClient = {
    <<(HttpMethod.POST)(path, callback)
  }

  def GET(path: String, callback: ((HttpResponse, String) => Unit)): HttpClient = {
    <<(HttpMethod.GET)(path, callback)
  }

  def <<(path: String, callback: ((HttpResponse, String) => Unit)): HttpClient = {
    <<(HttpMethod.GET)(path, callback)
  }

  def <<(method: HttpMethod)(path: String, callback: ((HttpResponse, String) => Unit)): HttpClient = {
    this synchronized {
      onResponses = onResponses ::: List(callback)
      while (inFlight) {
        // splitter doesn't support pipelining!
        this.wait()
      }
      inFlight = true
    }
    if (isClosed) {
      open()
    }
    request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, path)
    request.setHeader(HttpHeaders.Names.HOST, host + (if (port != 80) {
      ":" + port
    } else {
      ""
    }))
    request = supplementRequest(request)
    channel.write(request)
    isClosed = !HttpHeaders.isKeepAlive(request)
    this
  }

  def assertOk() {
    this synchronized {
      exceptions match {
        case Nil =>
        case head :: tail =>
          exceptions.foreach(_.printStackTrace())
          throw head
      }
    }
  }

  @volatile var closed = false

  def close() {
    closed = true
    this synchronized {
      this.wait()
    }
  }
}
