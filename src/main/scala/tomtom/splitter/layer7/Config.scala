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

import org.jboss.netty.channel.socket.nio.{NioServerSocketChannelFactory, NioClientSocketChannelFactory}
import java.util.concurrent.Executors
import java.util.UUID
import java.net.{SocketAddress, InetSocketAddress}
import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.codec.http.HttpRequest
import tomtom.splitter.config.Config
import java.io.File

/**Manages a host:port string, able to supply it as an InetSocketAddress. */
case class ProxiedServer(hostPort: String) {
  val address: SocketAddress = new InetSocketAddress(
    hostPort.split(":")(0), hostPort.split(":")(1).toInt)
}

/**
 * Mixes in all components necessary to configure the splitter proxy, and provides a mechanism to start & stop it.
 */
trait ProxyConfig extends ConnectionPoolFactoryComponent with ConnectionPoolComponent with InboundBootstrapComponent with MongoDbComponent with RequestMapperModule {
  val port: Int

  var serverChannel: Channel = _

  def start() {
    serverChannel = inboundBootstrap.bind(new InetSocketAddress(port))
  }

  def stop() {
    serverChannel.close.awaitUninterruptibly
  }
}

/**
 * Generates a ProxyConfig suitable for configuring the production splitter proxy.
 */
object ConfigFactory {

  def getConfig: ProxyConfig = {
    import Config.config
    new ProxyConfig {
      val executor = Executors.newCachedThreadPool
      val inboundSocketFactory = new NioServerSocketChannelFactory(executor, executor)
      val inboundBootstrap = new InboundBootstrap
      val port = config.int("port", 8080)
      val enableShadowing = config.bool("enableShadowing", true)

      val outboundChannelFactory = new NioClientSocketChannelFactory(executor, executor)
      val connectionFactory = new ConnectionPoolFactory
      val poolConfig = PoolConfig(
        maxOpenConnections = config.int("pool.maxOpenConnections", PoolConfig().maxOpenConnections),
        maxWaitMs = config.int("pool.maxWaitMs", PoolConfig().maxWaitMs),
        maxIdleConnections = config.int("pool.maxIdleConnections", PoolConfig().maxIdleConnections),
        msBetweenEvictionRuns = config.int("pool.msBetweenEvictionRuns", PoolConfig().msBetweenEvictionRuns),
        numTestsPerEvictionRuns = config.int("pool.numTestsPerEvictionRuns", PoolConfig().numTestsPerEvictionRuns),
        maxIdleTimeMs = config.int("pool.maxIdleTimeMs", PoolConfig().maxIdleTimeMs),
        connectTimeoutMillis = config.int("pool.connectTimeoutMillis", PoolConfig().connectTimeoutMillis),
        receiveTimeoutMillis = config.int("pool.receiveTimeoutMillis", PoolConfig().receiveTimeoutMillis),
        keepAlive = config.bool("pool.keepAlive", PoolConfig().keepAlive))
      val connectionPool = new ConnectionPoolImpl
      val reference = ProxiedServer(config.string("reference", "localhost:8080"))
      val shadow = ProxiedServer(config.string("shadow", "localhost:8080"))
      val sessionId = UUID.randomUUID.toString
      val mongoConfig = MongoConfig(
        config.string("mongo.host", "localhost"),
        config.int("mongo.port", 27017),
        config.string("mongo.db", "splitter"),
        enableShadowing,
        connsPerHost = config.int("mongo.connectionsPerHost", poolConfig.maxOpenConnections)
      )
      val dataSinkFactory = {
        config.stringOpt("capture") match {
          case Some("mongodb") => new MongoDb
          case Some("none") => new DataSinkFactory {
            override def dataSink(id: Int) = new ChainingLogSink(None)
          }
          case _ => sys.error("configuration 'dump' should be 'mongodb' or 'none'")
        }
      }

      val rewriteShadowUrl = config.stringOpt("rewriter") match {
        case Some("ofbiz") => RequestMapper.rewrite _: (HttpRequest => Option[HttpRequest])
        case _ => identity _: (HttpRequest => Option[HttpRequest])
      }

      val referenceHostname = config.stringOpt("referenceHostname")
      val shadowHostname = config.stringOpt("shadowHostname")
      val rewriteConfig = config.stringOpt("rewriteConfig").map(new File(_))
    }
  }
}
