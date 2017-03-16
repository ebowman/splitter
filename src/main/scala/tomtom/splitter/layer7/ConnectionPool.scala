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
import org.apache.commons.pool.impl.GenericKeyedObjectPool
import org.apache.commons.pool.KeyedPoolableObjectFactory
import org.slf4j.LoggerFactory

import org.jboss.netty._
import bootstrap.ClientBootstrap
import channel.{ChannelFactory, ChannelFuture, Channels, ChannelPipelineFactory, Channel}
import java.util.concurrent.ExecutorService

/**
 * @author Eric Bowman
 * @since 2011-03-28 08:56
 */

/**
 * Key to retrieve a connection to the server. It needs two things besides the details of the server:
 * a factory method to create a new ChannelPipelineFactory if there is no connection cached, and a method
 * to call once the connection was successful (or, if it is already connected).
 */
case class ConnectionKey(server: ProxiedServer,
                         pipelineFactory: () => ChannelPipelineFactory,
                         futureAction: (ChannelFuture => Unit)) {

  /**Equality is key here -- two ConnectionKeys just fall back to the embedded ProxiedServer for equality. */
  override def equals(other: Any): Boolean = {
    other match {
      case ConnectionKey(s, _, _) => server == s
      case _ => false
    }
  }

  /**Equality depends only on the embedded server. */
  override def hashCode: Int = server.hashCode
}

/**
 * Wraps the cached connection, so we can include some cache-specific metadata (in particular, whether or not
 * this connection was just created) so we work nicely with commons pool.
 */
case class CachedChannel(channel: Channel) {

  /**Set to true when a new connection is created, and cleared before it is handed back to the client. */
  @volatile var created: Boolean = true // note this is ignored by compiler-generated equals
}

/**DTO to hold both the key, and the channel retrieved by it. */
case class KeyChannelPair(key: ConnectionKey, obj: CachedChannel)

/**DTO manage the configurable settings for the cache pool, with reasonable defaults. */
case class PoolConfig(maxOpenConnections: Int = 30,
                      maxWaitMs: Int = 5000,
                      maxIdleConnections: Int = 8,
                      msBetweenEvictionRuns: Int = 10000,
                      numTestsPerEvictionRuns: Int = 5,
                      maxIdleTimeMs: Int = 60000,
                      connectTimeoutMillis: Int = 30000,
                      receiveTimeoutMillis: Int = 120000,
                      keepAlive: Boolean = true)

trait ConnectionPoolFactoryComponent {

  val executor: ExecutorService

  val outboundChannelFactory: ChannelFactory

  val poolConfig: PoolConfig

  class ConnectionPoolFactory extends KeyedPoolableObjectFactory[ConnectionKey, CachedChannel] {

    val log = Logger(LoggerFactory.getLogger(getClass))

    override def makeObject(key: ConnectionKey): CachedChannel = {
      log.info(s"Creating $key")
      val clientBootstrap = new ClientBootstrap(outboundChannelFactory)
      clientBootstrap.setPipelineFactory(key.pipelineFactory())
      clientBootstrap.setOption("connectTimeoutMillis", poolConfig.connectTimeoutMillis.toString)
      clientBootstrap.setOption("receiveTimeoutMillis", poolConfig.receiveTimeoutMillis.toString)
      clientBootstrap.setOption("keepAlive", poolConfig.keepAlive.toString)
      val future = clientBootstrap.connect(key.server.address)
      import RichFuture._
      future listen {
        key.futureAction
      }
      CachedChannel(future.getChannel)
    }

    override def destroyObject(key: ConnectionKey, obj: CachedChannel) {
      log.debug(s"Destroying $key")
      Channels.close(obj.channel)
    }

    override def validateObject(key: ConnectionKey, obj: CachedChannel): Boolean = {
      log.trace(s"Validating ($key, $obj)")
      val result = obj.created || (obj.channel.isConnected && obj.channel.isWritable)
      log.info(s"Validating $obj result is $result")
      result
    }

    override def activateObject(key: ConnectionKey, obj: CachedChannel) {
    }

    override def passivateObject(key: ConnectionKey, obj: CachedChannel) {
    }
  }

}

trait ConnectionPool {
  def borrowConnection(key: ConnectionKey): KeyChannelPair

  def returnConnection(keyedBundle: KeyChannelPair)
}

trait ConnectionPoolComponent {

  this: ConnectionPoolFactoryComponent =>

  val connectionFactory: ConnectionPoolFactory

  class ConnectionPoolImpl extends ConnectionPool {
    val log = Logger(LoggerFactory.getLogger(getClass))

    val pool = new GenericKeyedObjectPool(
      connectionFactory,
      poolConfig.maxOpenConnections, /* total number active instances per key */
      GenericKeyedObjectPool.WHEN_EXHAUSTED_BLOCK,
      poolConfig.maxWaitMs, /* max wait ms */
      poolConfig.maxIdleConnections, /* max idle instances per key */
      true, /* test on borrow */
      true, /* test on return */
      poolConfig.msBetweenEvictionRuns, /* time between eviction runs */
      poolConfig.numTestsPerEvictionRuns, /*NumTestsPerEvictionRun*/
      poolConfig.maxIdleTimeMs, /* MinEvictableIdleTimeMillis */
      true /* test while idle */)

    override def borrowConnection(key: ConnectionKey): KeyChannelPair = {
      val objBundle = pool.borrowObject(key)
      if (!objBundle.created) {
        val future = Channels.future(objBundle.channel)
        future.setSuccess()
        key.futureAction(future)
      } else {
        objBundle.created = false
      }
      val keyBundle = KeyChannelPair(key, objBundle)
      log.info(s"Borrowing $keyBundle for request")
      keyBundle
    }

    override def returnConnection(keyedBundle: KeyChannelPair) {
      if (keyedBundle != null) {
        log.info(s"Returning $keyedBundle")
        pool.returnObject(keyedBundle.key, keyedBundle.obj)
      }
    }
  }

}
