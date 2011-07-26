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

import org.slf4j.{Logger, LoggerFactory}
import org.jboss.netty.handler.codec.http.{HttpChunk, HttpResponse}
import org.jboss.netty.channel._

/**
 * Document me.
 *
 * @author Eric Bowman
 * @since 2011-03-28 09:24
 */
trait AbstractResponseHandler extends SimpleChannelUpstreamHandler {

  val sourceType: SourceType.SourceType
  val log: Logger

  @volatile var requestContext: RequestContext = null
  @volatile var binding: Binding = null
  @volatile var close = false

  override def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    e match {
      case message: MessageEvent => message.getMessage match {
        case RequestBinding(context, bnd) =>
          log.info("received RequestBinding {} {}", sourceType, (context.request.getUri, context.count))
          this.requestContext = context
          this.binding = bnd
        // swallow
        case _ => super.handleUpstream(ctx, e)
      }
      case _ => super.handleUpstream(ctx, e)
    }
  }

  override def messageReceived(ctx: ChannelHandlerContext,
                               e: MessageEvent) {
    e.getMessage match {
      case response: HttpResponse =>
        log.info("response message received (sinking) {} {}", (requestContext.request.getUri, requestContext.count, sourceType, e.getChannel), e)
        requestContext.dataSink.sinkResponse(sourceType, response)
        if (!requestContext.request.isChunked) {
          log.trace("Firing back RequestComplete {}", (requestContext.request.getUri, requestContext.count))
          ctx.sendDownstream(RequestComplete(requestContext))
        }
      case chunk: HttpChunk =>
        log.trace("chunk message received {} {}", (requestContext, sourceType, e.getChannel), e)
        requestContext.dataSink.sinkResponseChunk(sourceType, chunk)
        if (!chunk.isLast) {
          log.trace("Firing back RequestComplete {}", (requestContext.request.getUri, requestContext.count))
          ctx.sendDownstream(RequestComplete(requestContext))
        }
      case _ =>
        log.warn("Unexpected message: {} -> {}", (requestContext, sourceType, e.getChannel), e)
    }
    super.messageReceived(ctx, e)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    log.error("received failure {} {}", (requestContext, binding), e.getCause)
  }
}

class ReferenceResponseHandler(implicit override val sourceType: SourceType.SourceType) extends AbstractResponseHandler {

  override val log = LoggerFactory.getLogger(getClass)

  override def messageReceived(ctx: ChannelHandlerContext,
                               e: MessageEvent) {
    e.getMessage match {
      case response: HttpResponse =>
        response.setProtocolVersion(requestContext.incomingHttpVersion)
        close = requestContext.setInboundResponseHeaders(response)
        // todo trafficLock control flow
        val future = binding.inboundChannel.map(_.write(response)) // todo try again logic
        if (close && !response.isChunked) {
          future.foreach(_.addListener(ChannelFutureListener.CLOSE))
        }
      case chunk: HttpChunk =>
        val future = binding.inboundChannel.map(_.write(chunk))
        if (close && chunk.isLast) {
          future.foreach(_.addListener(ChannelFutureListener.CLOSE))
        }
      case _ =>
    }
    super.messageReceived(ctx, e)
  }
}

class ShadowResponseHandler(implicit override val sourceType: SourceType.SourceType) extends AbstractResponseHandler {
  override val log = LoggerFactory.getLogger(getClass)
}
