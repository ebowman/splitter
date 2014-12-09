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

package tomtom.splitter.layer4

import java.util.concurrent.Executors
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import org.jboss.netty._
import channel.socket.nio.{NioClientSocketChannelFactory, NioServerSocketChannelFactory}
import bootstrap.{ClientBootstrap, ServerBootstrap}
import buffer.{ChannelBuffers, ChannelBuffer}
import channel.{ChannelEvent, ChannelUpstreamHandler, Channel,
ChannelFutureListener, ChannelFuture, SimpleChannelUpstreamHandler,
MessageEvent, ChannelStateEvent, ChannelHandlerContext, ExceptionEvent,
Channels, ChannelPipelineFactory, ChannelPipeline}

case class Server(hostnamePort: String) {
  lazy val toAddress = new InetSocketAddress(
    hostnamePort.split(":")(0), hostnamePort.split(":")(1).toInt)
}

/**
 * Shadow proxy server.
 *
 * @author Eric Bowman
 * @since 2011-03-24 16:44
 */

object Proxy {

  val executor = Executors.newCachedThreadPool

  val clientSocketFactory = new NioClientSocketChannelFactory(executor, executor)

  def main(args: Array[String]) {
    require(args.length >= 3 && args.length % 2 == 1)

    val port = args(0).toInt
    val remotes = args.drop(1).map(Server).toList

    println("Proxying traffic to " + port + " to " + remotes)


    val serverBootstrap = new ServerBootstrap(
      new NioServerSocketChannelFactory(executor, executor))

    serverBootstrap.setPipelineFactory(
      new ChannelPipelineFactory {
        def getPipeline: ChannelPipeline = {
          val p = Channels.pipeline
          p.addLast("handler", new ProxyInboundHandler(remotes: _*))
          p
        }
      })

    serverBootstrap.bind(new InetSocketAddress(port))
  }
}

object ProxyInboundHandler {
  def closeOnFlush(ch: Channel) {
    if (ch.isConnected) {
      ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
    }
  }
}

import ProxyInboundHandler._

class ProxyInboundHandler(hosts: Server*) extends SimpleChannelUpstreamHandler {

  private val trafficLock = new Object
  @volatile private var outboundChannels = Nil: List[Channel]


  override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {

    // don't allow reading until we have the outbound sockets setup
    val inboundChannel = e.getChannel
    inboundChannel.setReadable(false)

    // setup a stream that will zip against hosts, with the appropriate
    // handler. The first one is primary, the rest are shadows.
    val outboundHandlers: Stream[ChannelUpstreamHandler] =
      new OutboundHandler(trafficLock, e.getChannel) #::
        Stream.continually[ChannelUpstreamHandler](new NullOutboundHandler)

    // keeps track of how many connections are made, so we can start reading
    // when they are all ready
    val connectedCount = new AtomicInteger

    // Open all the channels, with the appropriate outboundHandler attached
    // to
    val channels = for ((remoteServer, handler) <- hosts.zip(outboundHandlers)) yield {
      val clientBootstrap = new ClientBootstrap(Proxy.clientSocketFactory)
      clientBootstrap.getPipeline.addLast("handler", handler)

      // todo: implement open socket pooling
      val channelFuture = clientBootstrap.connect(remoteServer.toAddress)

      // once all the proxy channels are open, we can start to read from
      // the input channel
      channelFuture.addListener(new ChannelFutureListener {
        def operationComplete(future: ChannelFuture) {
          if (future.isSuccess) {
            if (connectedCount.incrementAndGet() == hosts.size) {
              inboundChannel.setReadable(true)
            }
          } else {
            inboundChannel.close()
          }
        }
      })
      channelFuture.getChannel
    }

    outboundChannels = channels.toList
  }

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val msg = e.getMessage.asInstanceOf[ChannelBuffer]
    trafficLock synchronized {
      outboundChannels foreach {
        channel => channel.write(msg)
        if (!channel.isWritable) {
          e.getChannel.setReadable(false)
        }
      }
    }
  }

  override def channelInterestChanged(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    trafficLock synchronized {
      if (e.getChannel.isWritable) {
        outboundChannels foreach (_.setReadable(true))
      }
    }
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    println("ProxyInboundHandler.channelClosed")
    outboundChannels.foreach(closeOnFlush)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getCause.printStackTrace()
    outboundChannels.foreach(closeOnFlush)
  }
}

/**
 * The outbound handler that binds the incoming request, to the "true"
 * backend system.
 */
class OutboundHandler(trafficLock: Object, inboundChannel: Channel) extends SimpleChannelUpstreamHandler {

  /**Writes a response from the backend back to the originating request. */
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val msg = e.getMessage.asInstanceOf[ChannelBuffer]
    trafficLock synchronized {
      inboundChannel.write(msg)
      if (!inboundChannel.isWritable) {
        e.getChannel.setReadable(false)
      }
    }
  }

  /**
   * Called when interesting things happen; makes sure that if we can write
   * to the incoming response, we should be sure to be reading stuff to write
   * back in the response
   */
  override def channelInterestChanged(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    trafficLock synchronized {
      if (e.getChannel.isWritable) {
        inboundChannel.setReadable(true)
      }
    }
  }

  /**
   * Outbound socket is closed, clean up the proxy socket.
   */
  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    closeOnFlush(inboundChannel)
  }

  /**
   * Something failed, tear down the socket.
   */
  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    e.getCause.printStackTrace()
    closeOnFlush(e.getChannel)
  }
}


/**
 * Outbound handler for shadow connections. For now just drops the data.
 */
class NullOutboundHandler extends ChannelUpstreamHandler {
  def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    ()
  }
}
