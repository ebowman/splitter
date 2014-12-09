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

import java.net.InetSocketAddress
import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.util.CharsetUtil
import org.jboss.netty.buffer.ChannelBuffers
import java.util.concurrent.ExecutorService
import org.jboss.netty.channel.{ChannelFuture, Channel, MessageEvent, ExceptionEvent, ChannelHandlerContext, SimpleChannelUpstreamHandler, Channels, ChannelPipeline, ChannelPipelineFactory, ChannelFutureListener}
import org.jboss.netty.handler.codec.http.{HttpChunk, HttpVersion, DefaultHttpResponse, HttpHeaders, HttpServerCodec, HttpResponseStatus, HttpRequest}

case class HttpServer(port: Int)(implicit executor: ExecutorService) {

  @volatile var lastFuture: ChannelFuture = _
  @volatile var callback: ((Either[HttpRequest, HttpChunk], StringBuilder) => HttpResponseStatus) = _
  @volatile var channel: Channel = _

  def makeResponse(request: HttpRequest,
                   buffer: StringBuilder,
                   status: HttpResponseStatus,
                   keepAlive: Boolean): List[AnyRef] = {
    val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
    response.setContent(ChannelBuffers.copiedBuffer(buffer.toString(), CharsetUtil.UTF_8))
    response.headers.set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8")
    if (keepAlive) {
      response.headers.set(HttpHeaders.Names.CONTENT_LENGTH, response.getContent.readableBytes)
    }
    List(response)
  }

  def onRequestChunk(chunk: HttpChunk) {}

  val bootstrap = {
    val b = new ServerBootstrap(new NioServerSocketChannelFactory(executor, executor))
    b.setPipelineFactory(new ChannelPipelineFactory {
      def getPipeline: ChannelPipeline = {
        val pipeline = Channels.pipeline
        pipeline.addLast("httpCodec", new HttpServerCodec)
        pipeline.addLast("processor", new SimpleChannelUpstreamHandler {
          var request: HttpRequest = _
          var readingChunks = false
          val buffer = new StringBuilder
          var status: HttpResponseStatus = _

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

              buffer.clear()
              status = callback(Left(request), buffer)
              if (request.isChunked) {
                readingChunks = true
              } else {
                writeResponse(e, requestId)
              }
            } else {
              val chunk = e.getMessage.asInstanceOf[HttpChunk]
              callback(Right(chunk), buffer)
              if (chunk.isLast) {
                writeResponse(e, None)
              }
            }
          }

          def writeResponse(e: MessageEvent, requestId: Option[Int]) {
            val keepAlive = HttpHeaders.isKeepAlive(this.request)
            for (obj <- makeResponse(this.request, buffer, status, keepAlive)) {
              lastFuture = e.getChannel.write(obj)
            }
            if (!keepAlive) {
              lastFuture.addListener(ChannelFutureListener.CLOSE)
            }
          }
        })
        pipeline
      }
    })
    b
  }


  def start(callback: ((Either[HttpRequest, HttpChunk], StringBuilder) => HttpResponseStatus)): HttpServer = {
    this.callback = callback
    channel = bootstrap.bind(new InetSocketAddress(port))
    this
  }

  def stop() {
    channel.close.awaitUninterruptibly
  }
}
