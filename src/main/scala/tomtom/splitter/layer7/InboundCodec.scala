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

import org.jboss.netty.channel._
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.util.CharsetUtil
import org.jboss.netty.buffer.ChannelBuffers._
import java.io.UnsupportedEncodingException

object InboundCodec {
  val SP: Byte = 32
  val CR: Byte = 13
  val LF: Byte = 10
  val CRLF = Array[Byte](CR, LF)
  val COLON: Byte = 58
  val LAST_CHUNK: ChannelBuffer = copiedBuffer("0\r\n\r\n", CharsetUtil.US_ASCII)
}

/**
 * We supply our own replacement for HttpServerCodec, so that we can translate
 * HTTP chunking into HTTP/1.0 streaming.
 */
class InboundCodec extends ChannelUpstreamHandler with ChannelDownstreamHandler {
  val decoder = new HttpRequestDecoder(4096, 8192, 8192)
  val encoder = new ProtocolConvertingResponseEncoder
  @volatile var protocol: HttpVersion = _
  @volatile var chunked = false

  import InboundCodec._

  def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    decoder.handleUpstream(ctx, e)
  }

  // InboundHandler chucks a downstream message to us, telling us what
  // was the version of the incoming http request. HttpMessageDecoder is
  // too closed to really hook into it, so we count on our downstream
  // friends to tell us what is going on.
  def handleDownstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    e match {
      case HttpVersionMessage(version) => protocol = version // swallow
      case _ => encoder.handleDownstream(ctx, e)
    }
  }

  // This is copied form HttpResponseEncoder, and the HttpChunk handling
  // logic is extended to support HTTP/1.0 streaming.
  class ProtocolConvertingResponseEncoder extends HttpResponseEncoder {
    override def encode(ctx: ChannelHandlerContext, channel: Channel, msg: AnyRef): AnyRef = {
      msg match {
        case httpMessage: HttpMessage =>
          chunked = httpMessage.isChunked
          super.encode(ctx, channel, msg)
        case chunk: HttpChunk =>
          if (chunked) {
            if (chunk.isLast) {
              chunked = false
              if (chunk.isInstanceOf[HttpChunkTrailer]) {
                protocol match {
                  case HttpVersion.HTTP_1_1 =>
                    val trailer = ChannelBuffers.dynamicBuffer(channel.getConfig.getBufferFactory)
                    trailer.writeByte('0'.asInstanceOf[Byte])
                    trailer.writeByte(CR)
                    trailer.writeByte(LF)
                    encodeTrailingHeaders(trailer, chunk.asInstanceOf[HttpChunkTrailer])
                    trailer.writeByte(CR)
                    trailer.writeByte(LF)
                    trailer
                  case HttpVersion.HTTP_1_0 =>
                    channel.close()
                    null
                }
              }
              else {
                protocol match {
                  case HttpVersion.HTTP_1_1 => LAST_CHUNK.duplicate
                  case HttpVersion.HTTP_1_0 =>
                    channel.close()
                    null
                }
              }
            }
            else {
              protocol match {
                case HttpVersion.HTTP_1_1 =>
                  val content = chunk.getContent
                  val contentLength = content.readableBytes
                  wrappedBuffer(
                    copiedBuffer(Integer.toHexString(contentLength), CharsetUtil.US_ASCII),
                    wrappedBuffer(CRLF),
                    content.slice(content.readerIndex, contentLength),
                    wrappedBuffer(CRLF))
                case HttpVersion.HTTP_1_0 => chunk.getContent
              }
            }
          }
          else {
            if (chunk.isLast) {
              null
            }
            else {
              chunk.getContent
            }
          }
        case _ =>
          super.encode(ctx, channel, msg)
      }
    }

    def encodeHeaders(buf: ChannelBuffer, message: HttpMessage): Unit = {
      try {
        import collection.JavaConverters._
        for (h <- message.getHeaders.asScala) {
          encodeHeader(buf, h.getKey, h.getValue)
        }
      }
      catch {
        case e: UnsupportedEncodingException => {
          throw new Error().initCause(e).asInstanceOf[Error]
        }
      }
    }

    def encodeTrailingHeaders(buf: ChannelBuffer, trailer: HttpChunkTrailer): Unit = {
      try {
        import collection.JavaConverters._
        for (h <- trailer.getHeaders.asScala) {
          encodeHeader(buf, h.getKey, h.getValue)
        }
      }
      catch {
        case e: UnsupportedEncodingException => {
          throw new Error().initCause(e).asInstanceOf[Error]
        }
      }
    }

    def encodeHeader(buf: ChannelBuffer, header: String, value: String): Unit = {
      buf.writeBytes(header.getBytes("ASCII"))
      buf.writeByte(COLON)
      buf.writeByte(SP)
      buf.writeBytes(value.getBytes("ASCII"))
      buf.writeByte(CR)
      buf.writeByte(LF)
    }
  }

}

