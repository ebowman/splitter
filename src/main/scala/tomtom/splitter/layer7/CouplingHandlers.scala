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

import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import java.nio.channels.ClosedChannelException
import org.jboss.netty.handler.codec.http.{HttpResponseStatus, HttpVersion, DefaultHttpResponse}
import org.jboss.netty.channel._

/**
 * Document me.
 *
 * @author Eric Bowman
 * @since 2011-03-28 09:29
 */

abstract class AbstractCouplingHandler extends SimpleChannelUpstreamHandler {

  val log: Logger

  import SourceType._

  def sourceType: SourceType

  @volatile var request: Option[RequestContext] = None
  @volatile var failure: Option[Throwable] = _
  @volatile var binding: Option[Binding] = None

  override def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    def isTrue(obj: AnyRef) = obj match { case java.lang.Boolean.TRUE => true; case _ => false }
    e match {
      case up: UpstreamChannelStateEvent if up.getState == ChannelState.OPEN && isTrue(up.getValue) =>
        failure = None
        request = None
      case message: MessageEvent => message.getMessage match {
        case requestBinding: RequestBinding =>
          log.info(s"received RequestBinding $sourceType ${
            (requestBinding.request.request.getUri, requestBinding.request.count)}")
          request = Some(requestBinding.request)
          binding = Some(requestBinding.binding)
          failure match {
            case Some(ex) =>
              log.info(s"Sinking error response $this")
              request.foreach(_.dataSink.sinkResponse(sourceType,
                new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR)))
              request.foreach(rc => ctx.sendDownstream(RequestComplete(rc)))
              binding.foreach(
                _.inboundChannel.map(
                  _.write(HttpErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR)).
                    addListener(ChannelFutureListener.CLOSE)))
            case None =>
          }
        case _ =>
      }
      case ex: ExceptionEvent =>
        failure = Some(ex.getCause)
        request match {
          case Some(rc) =>
            log.info(s"Sinking error response $this")
            rc.dataSink.sinkResponse(sourceType, HttpErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR))
          case None =>
        }
        request.foreach(rc => ctx.sendDownstream(RequestComplete(rc)))
        binding.foreach(_.inboundChannel.map(_.write(HttpErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR)).
          addListener(ChannelFutureListener.CLOSE)))
      case _ =>
    }
    super.handleUpstream(ctx, e)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getCause match {
      case e: ClosedChannelException =>
      // todo retry logic?
      case _ =>
    }
    log.error(s"received failure ${
      (request.map(_.request.getUri).getOrElse("(No request in flight yet)"), failure.getOrElse(e.getCause))} $e")
    request.map(RequestComplete).foreach(ctx.sendDownstream)
    binding foreach { bnd =>
      bnd.inboundChannel foreach { channel =>
        channel.write(HttpErrorResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR)).addListener(ChannelFutureListener.CLOSE)
      }
    }
    super.exceptionCaught(ctx, e)
  }
}

class ReferenceCouplingHandler extends AbstractCouplingHandler {

  override val log = Logger(LoggerFactory.getLogger(classOf[ReferenceCouplingHandler]))

  override val sourceType = SourceType.Reference

  override def channelInterestChanged(ctx: ChannelHandlerContext,
                                      e: ChannelStateEvent) {
    if (log.underlying.isTraceEnabled) {
      log.trace(s"interestChanged ${request.map(_.count)} ${(e, ctx)}")
    }

    binding foreach { bnd =>
      bnd.inboundChannel foreach { channel =>
        bnd.trafficLock.synchronized {
          channel.setReadable(bnd.outboundChannel.isWritable)
        }
      }
    }
  }
}

class ShadowCouplingHandler extends AbstractCouplingHandler {

  override val log = Logger(LoggerFactory.getLogger(classOf[ShadowCouplingHandler]))

  override val sourceType = SourceType.Shadow
}
