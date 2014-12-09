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

import java.util.concurrent.Executors
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import java.net.InetSocketAddress
import org.jboss.netty.util.CharsetUtil
import collection.JavaConverters._
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.http.{HttpServerCodec, CookieEncoder, CookieDecoder, HttpChunkTrailer, HttpChunk, QueryStringDecoder, HttpResponseStatus, DefaultHttpResponse, HttpVersion, HttpHeaders, HttpRequest}
import org.jboss.netty.channel.{ExceptionEvent, ChannelFutureListener, MessageEvent, ChannelHandlerContext, SimpleChannelUpstreamHandler, Channels, ChannelPipeline, ChannelPipelineFactory}
import java.util.concurrent.atomic.AtomicInteger

object Server {

  val executor = Executors.newCachedThreadPool
  val idGenerator = new AtomicInteger
  val rnd = util.Random

  def delayTime: Int = {
    // we take a Poisson distribution with lamba=1,
    // and compute the cumulative density, and use that
    // to generate a poisson distribution using the uniform
    // random number generator
    val max = 10d
    val x = max * rnd.nextDouble()
    val y = 1 - math.exp(-x) * (1 + x)
    var done = false
    var z = 0d
    while (!done) {
      z = max * rnd.nextDouble()
      if (z <= y) {
        done = true
      }
    }
    val scale = 500
    math.round(z * scale).toInt
  }

  def main(args: Array[String]) {
    val bootstrap = {
      val b = new ServerBootstrap(new NioServerSocketChannelFactory(executor, executor))
      b.setPipelineFactory(new ChannelPipelineFactory {
        def getPipeline: ChannelPipeline = {
          val pipeline = Channels.pipeline
          pipeline.addLast("httpCodec", new HttpServerCodec)
          pipeline.addLast("processor", new SimpleChannelUpstreamHandler {
            var request: HttpRequest = _
            var readingChunks = false
            var buffer = new StringBuilder


            override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
              e.getCause.printStackTrace()
              e.getChannel.close()
            }

            override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
              if (!readingChunks) {
                this.request = e.getMessage.asInstanceOf[HttpRequest]
                if (HttpHeaders.is100ContinueExpected(this.request)) {
                  e.getChannel.write(new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE))
                }

                val requestId: Option[Int] = {
                  if (request.headers.get("X-Request-Id") != null) {
                    Some(request.headers.get("X-Request-Id").toInt)
                  } else {
                    None
                  }
                }

                println("RequestId = " + requestId)
                Thread.sleep(delayTime)

                buffer.setLength(0)
                buffer.append("Welcome\r\n")
                buffer.append("=======\r\n")
                buffer.append("VERSION: " + request.getProtocolVersion + "\r\n")
                buffer.append("HOSTNAME: " + HttpHeaders.getHost(request, "unknown") + "\r\n")
                buffer.append("REQUEST_URI: " + request.getUri + "\r\n\r\n")
                request.headers.asScala.foreach {
                  h => buffer.append("HEADER: " + h.getKey + "=" + h.getValue + "\r\n")
                }
                buffer.append("\r\n")
                val queryDecoder = new QueryStringDecoder(request.getUri)
                val params = queryDecoder.getParameters
                if (!params.isEmpty) {
                  for (headers <- params.entrySet.asScala;
                       name = headers.getKey;
                       value <- headers.getValue.asScala) {
                    buffer.append("PARAM: " + name + "=" + value + "\r\n")
                  }
                  buffer.append("\r\n")
                }

                if (request.isChunked) {
                  readingChunks = true
                } else {
                  val content = request.getContent
                  if (content.readable) {
                    buffer.append("CONTENT: " + content.toString(CharsetUtil.UTF_8) + "\r\n")
                  }
                  writeResponse(e, requestId)
                }
              } else {
                val chunk = e.getMessage.asInstanceOf[HttpChunk]
                if (chunk.isLast) {
                  val trailer = chunk.asInstanceOf[HttpChunkTrailer]
                  if (!trailer.trailingHeaders().isEmpty) {
                    for (name <- trailer.trailingHeaders.names.asScala;
                         value <- trailer.trailingHeaders.getAll(name).asScala) {
                      buffer.append("TRAILING HEADER: " + name + "=" + value + "\r\n")
                    }
                    buffer.append("\r\n")
                  }
                  writeResponse(e, None)
                } else {
                  buffer.append("CHUNK: " + chunk.getContent.toString(CharsetUtil.UTF_8) + "\r\n")
                }
              }
            }

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
              val cookieString = request.headers.get(HttpHeaders.Names.COOKIE)
              if (cookieString != null) {
                val cookies = new CookieDecoder().decode(cookieString).asScala
                if (cookies.nonEmpty) {
                  val encoder = new CookieEncoder(/* server= */ true)
                  cookies.foreach(encoder.addCookie)
                  response.headers.set(HttpHeaders.Names.SET_COOKIE, encoder.encode)
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
      b
    }

    val port = if (args.length > 0) {
      args(0).toInt
    } else {
      8080
    }
    println("Binding to " + port)
    bootstrap.bind(new InetSocketAddress(port))
  }
}
