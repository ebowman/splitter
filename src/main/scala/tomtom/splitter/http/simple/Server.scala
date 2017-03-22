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

package tomtom.splitter.http.simple

import collection.JavaConverters._
import java.net.InetSocketAddress
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.Executors

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.channel._
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.codec.http.cookie._
import org.jboss.netty.util.CharsetUtil

abstract class Server {

  val executor = Executors.newCachedThreadPool
  val idGenerator = new AtomicInteger
  val rnd = scala.util.Random
  private val CRLF = "\r\n"

  def delayTime(): Int = {
    // we take a Poisson distribution with lamba=1,
    // and compute the cumulative density, and use that
    // to generate a poisson distribution using the uniform
    // random number generator
    val max = 10d
    val x = max * rnd.nextDouble()
    val y = 1 - math.exp(-x) * (1 + x)

    @scala.annotation.tailrec
    def findRnd(): Double = {
      val z = max * rnd.nextDouble()
      if (z <= y) z
      else findRnd()
    }

    val z = findRnd()
    val scale = 500
    math.round(z * scale).toInt
  }

  def bootstrap(port: Int) = {
    val b = new ServerBootstrap(new NioServerSocketChannelFactory(executor, executor))
    b.setPipelineFactory(new ChannelPipelineFactory {
      def getPipeline: ChannelPipeline = {
        val pipeline = Channels.pipeline
        pipeline.addLast("httpCodec", new HttpServerCodec)
        pipeline.addLast("processor", new SimpleChannelUpstreamHandler {
          val requestRef = new AtomicReference[HttpRequest]
          val buffer = new StringBuilder


          override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
            e.getCause.printStackTrace()
            e.getChannel.close()
          }

          type Handler = (ChannelHandlerContext, MessageEvent) => Unit

          val currHandler = new AtomicReference[Handler](defaultHandler)

          private def defaultHandler(ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
            this.requestRef.set(e.getMessage.asInstanceOf[HttpRequest])
            val request = requestRef.get
            if (HttpHeaders.is100ContinueExpected(request)) {
              e.getChannel.write(new DefaultHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE))
            }

            val requestId: Option[Int] = {
              Option(request.headers.get("X-Request-Id")).map(_.toInt)
            }

            println("RequestId = " + requestId)
            Thread.sleep(delayTime())

            buffer.setLength(0)
            buffer.append(s"Welcome$CRLF")
            buffer.append(s"=======$CRLF")
            buffer.append(s"VERSION: ${request.getProtocolVersion}$CRLF")
            buffer.append(s"HOSTNAME: ${HttpHeaders.getHost(request, "unknown")}$CRLF")
            buffer.append(s"REQUEST_URI: ${request.getUri}$CRLF$CRLF")
            request.headers.asScala.foreach {
              h => buffer.append(s"HEADER: ${h.getKey}=${h.getValue}$CRLF")
            }
            buffer.append(CRLF)
            val queryDecoder = new QueryStringDecoder(request.getUri)
            val params = queryDecoder.getParameters
            if (!params.isEmpty) {
              for (headers <- params.entrySet.asScala;
                   name = headers.getKey;
                   value <- headers.getValue.asScala) {
                buffer.append(s"PARAM: $name=$value$CRLF")
              }
              buffer.append(CRLF)
            }

            if (request.isChunked) {
              currHandler.set(chunkHandler)
            } else {
              val content = request.getContent
              if (content.readable) {
                buffer.append(s"CONTENT: ${content.toString(CharsetUtil.UTF_8)}$CRLF")
              }
              writeResponse(e, requestId)
            }
          }

          private def chunkHandler(ctx: ChannelHandlerContext, e: MessageEvent): Unit = {
            val chunk = e.getMessage.asInstanceOf[HttpChunk]
            if (chunk.isLast) {
              val trailer = chunk.asInstanceOf[HttpChunkTrailer]
              if (!trailer.trailingHeaders().isEmpty) {
                for (name <- trailer.trailingHeaders.names.asScala;
                     value <- trailer.trailingHeaders.getAll(name).asScala) {
                  buffer.append(s"TRAILING HEADER: $name=$value$CRLF")
                }
                buffer.append(CRLF)
              }
              writeResponse(e, None)
            } else {
              buffer.append(s"CHUNK: ${chunk.getContent.toString(CharsetUtil.UTF_8)}$CRLF")
            }
          }

          override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) = currHandler.get()(ctx, e)

          def writeResponse(e: MessageEvent, requestId: Option[Int]) {
            val keepAlive = false // HttpHeaders.isKeepAlive(this.request)
            val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
            response.setContent(ChannelBuffers.copiedBuffer(buffer.toString(), CharsetUtil.UTF_8))
            response.headers.set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8")
            response.headers.set("X-Response-Id", idGenerator.incrementAndGet.toString)
            // response.headers.set("Connection", "close")
            if (keepAlive) {
              response.headers.set(HttpHeaders.Names.CONTENT_LENGTH, response.getContent.readableBytes)
            }
            val request = requestRef.get()
            Option(request.headers.get(HttpHeaders.Names.COOKIE)).foreach { cookieString =>
              val cookies = ServerCookieDecoder.LAX.decode(cookieString).asScala
              if (cookies.nonEmpty) {
                val encoder = ServerCookieEncoder.LAX
                response.headers.set(HttpHeaders.Names.SET_COOKIE, cookies.map(encoder.encode).asJava)
              }
            }
            requestId.map(response.headers.set("X-Request-Id", _))

            val future = e.getChannel.write(response)
            if (!keepAlive) {
              future.addListener(ChannelFutureListener.CLOSE)
            }
          }
        })
        pipeline
      }
    })
    b.bind(new InetSocketAddress(port))
  }
}

object Server extends Server with App {
  val port = if (args.nonEmpty) {
    args(0).toInt
  } else {
    8080
  }
  println("Binding to " + port)
  bootstrap(port)
}
