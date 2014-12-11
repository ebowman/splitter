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

import org.jboss.netty._
import bootstrap.ServerBootstrap
import buffer.ChannelBuffers
import channel._
import handler.codec.http.{HttpResponse, HttpRequest, HttpHeaders, DefaultHttpResponse, HttpResponseStatus, HttpVersion, HttpChunk}
import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, LinkedBlockingQueue}

import SourceType._
import java.text.SimpleDateFormat
import java.nio.charset.Charset

object InboundState extends Enumeration {
  type InboundState = Value
}

case class HttpErrorResponse(status: HttpResponseStatus,
                             exception: Option[String] = None) extends DefaultHttpResponse(HttpVersion.HTTP_1_1, status) {
  val sdf = new SimpleDateFormat("dd MMM dd yyyy hh:mm:ss z")
  sdf.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))
  headers.set(HttpHeaders.Names.DATE, sdf.format(new java.util.Date))
  headers.set(HttpHeaders.Names.SERVER, getClass.getPackage.getImplementationVersion match {
    case null => "splitter-dev"
    case version => version
  })
  headers.set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8")
  val content = ChannelBuffers.copiedBuffer(exception.getOrElse(status.toString + "\n"), Charset.forName("UTF-8"))
  headers.set(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes())
  setContent(content)
}

case class Binding(connectionPool: ConnectionPool,
                   trafficLock: Object,
                   connectionKey: ConnectionKey,
                   inboundChannel: Option[Channel],
                   outboundChannel: Channel) {
  def close() {
    LoggerFactory.getLogger(getClass).info("Returning binding {}", this)
    val obj = CachedChannel(outboundChannel)
    obj.created = false
    connectionPool.returnConnection(KeyChannelPair(connectionKey, obj))
  }
}

case class RequestContext(count: Int,
                          request: HttpRequest,
                          dataSink: DataSink) {

  @volatile var content: List[HttpChunk] = Nil

  val incomingHttpVersion: HttpVersion = if (request != null) {
    request.getProtocolVersion
  } else {
    null
  }

  val incomingConnectionHeader = if (request != null) {
    import HttpHeaders.Names._
    request.headers.get(CONNECTION)
  } else {
    null
  }

  if (request != null) {
    request.headers.set(HttpHeaders.Names.CONNECTION, "Keep-Alive")
    request.setProtocolVersion(HttpVersion.HTTP_1_1)
    request.headers.set("X-Request-Id", count.toString)
  }

  /**Mutates the response! */
  def setInboundResponseHeaders(response: HttpResponse): Boolean = {
    import HttpHeaders.Names._
    val responseConnection = response.headers.get(CONNECTION)
    response.headers.remove(CONNECTION)
    response.headers.remove("Keep-Alive")
    incomingHttpVersion match {
      case HttpVersion.HTTP_1_0 =>
        response.headers.set(CONNECTION, "close")
        response.headers.remove(TRANSFER_ENCODING)
        true
      case HttpVersion.HTTP_1_1 =>
        if (incomingConnectionHeader != "close" && responseConnection != "close") {
          response.headers.set(CONNECTION, "Keep-Alive")
          false
        } else {
          response.headers.set(CONNECTION, "close")
          true
        }
    }
  }
}

object ChannelClosedRequest extends RequestContext(Int.MinValue, null, null) {
  override def toString = "ChannelClosedRequest"
}

case class RequestBinding(request: RequestContext, binding: Binding) extends MessageEvent {
  def getRemoteAddress = null

  def getMessage = this

  def getFuture = null

  def getChannel = null
}

case class RequestComplete(request: RequestContext) extends MessageEvent {
  def getRemoteAddress = null

  def getMessage = this

  def getFuture = null

  def getChannel = null
}

object InboundClosed extends MessageEvent {
  def getRemoteAddress = null

  def getMessage = this

  def getFuture = null

  def getChannel = null
}

case class HttpVersionMessage(version: HttpVersion) extends MessageEvent {
  def getRemoteAddress = null

  def getMessage = this

  def getFuture = null

  def getChannel = null
}

trait InboundBootstrapComponent {

  val inboundBootstrap: InboundBootstrap
  val inboundSocketFactory: ChannelFactory
  val connectionPool: ConnectionPool
  val reference: ProxiedServer
  val shadow: ProxiedServer
  val dataSinkFactory: DataSinkFactory
  val rewriteShadowUrl: (HttpRequest => Option[HttpRequest])
  val enableShadowing: Boolean
  val referenceHostname: Option[String]

  def rewriteReference(request: HttpRequest): HttpRequest = {
    referenceHostname.foreach(request.headers.set("Host", _))
    request
  }

  class InboundBootstrap extends ServerBootstrap(inboundSocketFactory) {
    private val log = Logger(LoggerFactory.getLogger(getClass))
    setPipelineFactory(new ChannelPipelineFactory {
      def getPipeline: ChannelPipeline = {
        try {
          val pipeline = Channels.pipeline
          pipeline.addLast("http_inbound", new InboundCodec)
          pipeline.addLast("inbound_handler", new InboundHandler(
            connectionPool, reference, shadow, dataSinkFactory))
          pipeline
        } catch {
          case e: Exception =>
            log.error("Could not bootstrap inbound", e)
            throw e
        }
      }
    })
  }

  object InboundHandler {
    val log = Logger(LoggerFactory.getLogger(classOf[InboundHandler]))
    val counter = new AtomicInteger
    val referenceKey =
      ConnectionKey(reference, {
        () => new ReferenceChannelPipelineFactory
      }, null)

    val shadowKey = ConnectionKey(shadow, {
      () => new ShadowChannelPipelineFactory
    }, null)

    val executor = Executors.newCachedThreadPool
  }

  class InboundHandler(connectionPool: ConnectionPool,
                       reference: ProxiedServer,
                       shadow: ProxiedServer,
                       dataSinkFactory: DataSinkFactory) extends SimpleChannelUpstreamHandler {

    import InboundHandler.{log, referenceKey, shadowKey, counter, executor}

    val referenceLock = new Object
    val shadowLock = new Object
    val requestLock = new Object

    @volatile var referenceMetadata: KeyChannelPair = _
    @volatile var shadowMetadata: KeyChannelPair = _

    @volatile var inboundChannel: Channel = _
    @volatile var referenceBinding: Binding = _
    @volatile var shadowBinding: Binding = _

    @volatile var request: RequestContext = _
    @volatile var shadowRequest: Option[RequestContext] = None

    val pendingRequests = new LinkedBlockingQueue[RequestContext]

    def referenceChannel(): Option[Channel] = Option(referenceBinding).map(_.outboundChannel)

    override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      inboundChannel = e.getChannel
      log.trace(s"channel opened, setting readable to false $inboundChannel")
      inboundChannel.setReadable(false)

      val counter = new AtomicInteger(2)
      referenceMetadata = connectionPool.borrowConnection(
        referenceKey.copy(futureAction = {
          case future: ChannelFuture =>
            val setReadable = counter.decrementAndGet() == 0
            log.info(s"On callback, setReadable = $setReadable")
            if (future.isSuccess) {
              log.info(s"Reference connection success $inboundChannel")
              log.trace(s"Setting inbound channel readable $inboundChannel")
              referenceLock synchronized {
                referenceBinding = Binding(connectionPool, referenceLock, referenceKey, Some(inboundChannel), future.getChannel)
                if (setReadable) {
                  inboundChannel.setReadable(true)
                }
              }
            } else {
              log.error(s"Reference connection failed $inboundChannel")
              if (setReadable) {
                referenceLock synchronized {
                  inboundChannel.setReadable(true)
                }
              }
            }
        }))

      if (counter.decrementAndGet() == 0) {
        log.info("Setting readable true in normal flow")
        referenceLock synchronized {
          inboundChannel.setReadable(true)
        }
      }

      log.trace(s"Got outbound reference connection: $inboundChannel")

      // Even if we choose not to forward a single request, we still have to
      // bring up the infrastructure.
      if (enableShadowing) {
        log.info(s"Initiating shadow processor for inbound $inboundChannel")
        initiateShadowProcessor()
      }
    }

    def initiateShadowProcessor() {
      def startShadowProcessor(outboundChannel: Channel) {
        shadowBinding = Binding(connectionPool, shadowLock, shadowKey, None, outboundChannel)
        log.info("startShadowProcessing; submitting looping task")
        executor.submit(new Runnable {
          override def run() {
            var done = false
            var somethingBound = false
            log.info("Entered looping task")
            while (!done) {
              try {
                log.info("Trying to take")
                val request = pendingRequests.take
                log.info(s"Took: $request")
                if (request == ChannelClosedRequest) {
                  if (somethingBound) {
                    outboundChannel.write(InboundClosed)
                  } else {
                    shadowBinding.close()
                  }
                  log.info("Task complete servicing outbound shadow requests")
                  done = true
                } else {
                  log.info(s"Writing shadow request $request to $outboundChannel")
                  somethingBound = true
                  outboundChannel.write(RequestBinding(request, shadowBinding))
                }
              } catch {
                case e: InterruptedException =>
                  log.warn("shadow executor interrupted")
                  done = true
                case e: Exception =>
                  log.error(s"Exception processing shadow: ", e)
              } finally {
                log.info("Leaving long-running shadow pump task")
              }
            }
          }
        })
        log.info("looping task submitted")
      }

      /**
       * Empties out pendingRequests, and sinks them as errors, returning true if
       * nobody has sent a ChannelClosedRequest into the pendingRequests queue.
       */
      def drainAndFlush: Boolean = {
        log.info("Executing a drain & flush")
        val pending = new util.ArrayList[RequestContext]
        pendingRequests.drainTo(pending)
        import collection.JavaConverters._
        val (closed, open) = pending.asScala.partition(_ == ChannelClosedRequest)
        open foreach {
          request =>
            log.info(s"Sinking a gateway timeout for $request")
            request.dataSink.sinkRequest(Shadow, request.request)
            request.dataSink.sinkResponse(Shadow, HttpErrorResponse(HttpResponseStatus.GATEWAY_TIMEOUT))
        }
        closed.size == 0
      }

      @volatile var connectionStart = System.currentTimeMillis
      @volatile var falloff = 50
      lazy val futureShadowAction: (ChannelFuture => Unit) = {
        case future: ChannelFuture =>
          if (future.isSuccess) {
            log.info(s"Shadow connection success $inboundChannel")
            startShadowProcessor(future.getChannel)
          } else {
            log.warn(s"Shadow connection failed $inboundChannel")
            // We can just borrow again; previous connection will be
            // automatically reaped when it is noticed it is not connected
            Thread.sleep(falloff)
            falloff *= 2
            if (falloff > 4000) {
              falloff = 50
            }

            var connectionStillOpen = true
            if (System.currentTimeMillis - connectionStart > 5000) {
              log.info("Timeout; draining and reramping")
              connectionStillOpen = drainAndFlush
              connectionStart = System.currentTimeMillis
            }
            if (connectionStillOpen) {
              if (shadowMetadata != null) {
                log.info(s"Returning $shadowMetadata")
                connectionPool.returnConnection(shadowMetadata)
              }
              log.info(s"Borrowing another shadow with falloff = $falloff")
              shadowMetadata = connectionPool.borrowConnection(
                shadowKey.copy(futureAction = futureShadowAction))
            }
          }
      }

      log.info("Submitting runnable to borrow shadow connection")
      executor.submit(new Runnable {
        override def run() {
          log.info("Running task to borrow shadow connection")
          try {
            shadowMetadata = connectionPool.borrowConnection(
              shadowKey.copy(futureAction = futureShadowAction))
            log.info(s"Borrowed shadowMetadata $shadowMetadata")
          } catch {
            case e: NoSuchElementException =>
              log.info("oops, NoSuchElement")
              drainAndFlush
            case e: Exception =>
              log.info(s"oops, unknown exception: $e")
              log.error("Unknown exception borrowing connection", e)
          } finally {
            log.info("Done running task to borrow shadow connection")
          }
        }
      })
      log.info("Runnable to borrow connection shadow submitted")
    }

    override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      log.info(s"channelClosed $inboundChannel")
      if (enableShadowing) {
        pendingRequests.put(ChannelClosedRequest)
      }
      if (referenceBinding != null) {
        referenceBinding.close()
      }
      referenceMetadata = null
      shadowMetadata = null
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      log.error(s"Exception caught in InboundHandler: $inboundChannel", e.getCause)
    }

    override def channelInterestChanged(ctx: ChannelHandlerContext,
                                        e: ChannelStateEvent) {
      log.info(s"interestChanged: $inboundChannel $e")
      referenceLock synchronized {
        if (inboundChannel.isWritable) {
          log.info(s"Setting reference channel readable $inboundChannel")
          referenceChannel().map(_.setReadable(true))
        } else {
          log.info(s"Setting reference channel not readable $inboundChannel")
          referenceChannel().map(_.setReadable(false))
        }
      }
    }

    /**
     * Called on the inbound+upstream pipeline, once a request object has
     * been assembled for us to digest
     */
    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {

      if (referenceBinding == null) {
        inboundChannel.write(HttpErrorResponse(HttpResponseStatus.BAD_GATEWAY)).addListener(ChannelFutureListener.CLOSE)
        return
      } else {
        channelInterestChanged(null, null)
      }

      if (request == null) {
        // blowing chunks?
        val count = counter.incrementAndGet


        val httpRequest = rewriteReference(e.getMessage.asInstanceOf[HttpRequest])
        ctx.sendDownstream(HttpVersionMessage(httpRequest.getProtocolVersion))
        request = RequestContext(count, httpRequest, dataSinkFactory.dataSink(count))
        log.info(s"messageReceived: ${request.request.getUri} ${request.count}")

        if (enableShadowing) {
          shadowRequest = rewriteShadowUrl(httpRequest) match {
            case None => None
            case Some(r) => Some(request.copy(request = r))
          }
          log.info(s"shadowRequest from $httpRequest = $shadowRequest")
        }

        if (HttpHeaders.is100ContinueExpected(httpRequest)) {
          e.getChannel.write(new DefaultHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE))
        }

        if (!httpRequest.isChunked) {
          assembleOutbound()
        }
      } else {
        // readingChunks
        val chunk = e.getMessage.asInstanceOf[HttpChunk]
        if (log.underlying.isTraceEnabled) {
          log.trace(s"Reading chunk $inboundChannel $chunk")
        }
        request.content ::= chunk
        if (chunk.isLast) {
          assembleOutbound()
        }
      }
    }

    def assembleOutbound() {
      referenceChannel().map(_.write(RequestBinding(request, referenceBinding)))

      if (enableShadowing) {
        shadowRequest match {
          case None =>
            log.info("shadowRequest is None; sinking PRECONDITION_FAILED")
            request.dataSink.sinkRequest(Shadow, request.request)
            request.dataSink.sinkResponse(Shadow,
              new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PRECONDITION_FAILED))
          case Some(sr) =>
            log.info(s"Submitting pending shadow request $sr")
            pendingRequests.put(sr)
        }
        shadowRequest = None
      }
      request = null

      referenceLock synchronized {
        if (!referenceBinding.outboundChannel.isWritable) {
          log.trace(s"setting inbound channel not readable $inboundChannel")
          inboundChannel.setReadable(false)
        }
      }
    }
  }

}


