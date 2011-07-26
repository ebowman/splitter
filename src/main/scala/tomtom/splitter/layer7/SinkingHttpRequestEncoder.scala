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

import org.slf4j.LoggerFactory
import org.jboss.netty.channel.{Channel, MessageEvent, ChannelEvent, ChannelHandlerContext}
import org.jboss.netty.handler.codec.http.{HttpChunk, HttpRequest, HttpRequestEncoder}

/**
 * Intercepts the outbound request, and sinks it.
 *
 * @author Eric Bowman
 * @since 2011-03-29 20:37
 */
class SinkingHttpRequestEncoder(implicit source: SourceType.SourceType) extends HttpRequestEncoder {

  val log = LoggerFactory.getLogger(getClass)

  val requestQueue = collection.mutable.Queue[RequestBinding]()
  var requestContext: RequestContext = _
  var binding: Binding = _
  @volatile var inboundClosed = false

  override def handleDownstream(ctx: ChannelHandlerContext, evt: ChannelEvent) {
    evt match {
      case message: MessageEvent => message.getMessage match {
        // When we see a RequestBinding, we either
        case requestBinding: RequestBinding =>
          log.trace("Received RequestBinding {}", requestBinding)
          this synchronized {
            require(!inboundClosed)
            if (requestContext == null) {
              if (log.isInfoEnabled) {
                log.info("Taking a binding {} {}", source, (requestBinding.request.request.getUri, requestBinding.request.count))
              }
              requestContext = requestBinding.request
              binding = requestBinding.binding
              Some(requestBinding)
            } else {
              if (log.isInfoEnabled) {
                log.info("Queueing a binding {} {}", source, (requestBinding.request.request.getUri, requestBinding.request.count))
              }
              requestQueue.enqueue(requestBinding)
              None
            }
          }.foreach {
            rb =>
              ctx.sendUpstream(evt)
              val channel = ctx.getChannel
              val rc = requestContext
              if (rc != null) {
                log.info("Writing to channel {}: {}", channel, rc.request)
                channel.write(rc.request)
                rc.content foreach {
                  channel.write(_)
                }
              }
          }
        case RequestComplete(request) =>
          log.trace("Received request complete {}", request)
          this synchronized {
            if (requestQueue.size == 0) {
              log.info("Got RequestCompleted, queue empty {}", (source, request.request.getUri, request.count))
              if (inboundClosed && binding != null) {
                binding.close()
                binding = null
                inboundClosed = false
              }
              requestContext = null
              None
            } else {
              log.info("Got RequestCompleted, queue full {}", (source, request.request.getUri, request.count))
              val requestBinding = requestQueue.dequeue()
              requestContext = requestBinding.request
              binding = requestBinding.binding
              Some(requestBinding)
            }
          }.foreach {
            rb =>
              ctx.sendUpstream(rb)
              val channel = ctx.getChannel
              channel.write(requestContext.request)
              requestContext.content foreach {
                channel.write(_)
              }
          }
        case InboundClosed =>
          log.trace("Received InboundClosed")
          this synchronized {
            if (binding != null && requestContext == null && requestQueue.isEmpty) {
              binding.close()
              binding = null
            } else {
              inboundClosed = true
            }
          }
        case _ => super.handleDownstream(ctx, evt)
      }
      case _ => super.handleDownstream(ctx, evt)
    }
  }

  override def encode(ctx: ChannelHandlerContext,
                      channel: Channel,
                      msg: AnyRef): AnyRef = {
    if (msg != null) {
      msg match {
        case request: HttpRequest =>
          log.info("{} Encoding request (sinking) ({})", source, (requestContext.request.getUri, requestContext.count))
          requestContext.dataSink.sinkRequest(source, request)
        case chunk: HttpChunk =>
          log.info("{} Encoding chunk (sinking) ({})", source, (requestContext.request.getUri, requestContext.count))
          requestContext.dataSink.sinkRequestChunk(source, chunk)
        case _ =>
      }
    }
    super.encode(ctx, channel, msg)
  }

}