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

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import org.scalatest.WordSpec
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scalatest.matchers.MustMatchers
import org.jboss.netty.buffer.DynamicChannelBuffer
import org.jboss.netty.handler.codec.http.{HttpRequest, DefaultHttpChunk, DefaultHttpChunkTrailer, HttpChunk, HttpHeaders, HttpVersion, DefaultHttpResponse, HttpResponseStatus}

/**
 * Test to verify and demonstrate our mini http server & client fixtures.
 *
 * @author Eric Bowman
 * @since 2011-04-06 13:17
 */

@RunWith(classOf[JUnitRunner])
class BasicClientServerFixtureTest extends WordSpec with MustMatchers {

  val serverPort = 8484
  implicit val executor = Executors.newCachedThreadPool
  "The testing infrastructure" should {

    "should support a basic in-test client/server" in {
      val counter = new AtomicInteger
      val s = HttpServer(serverPort).start {
        case (Left(request), buffer: StringBuilder) =>
          buffer.append(counter.incrementAndGet.toString)
          HttpResponseStatus.OK
        case _ => HttpResponseStatus.OK
      }

      val responses = Array("", "")

      val client = HttpClient("localhost", serverPort) <<
        ("/path", {
          case (_, resp) => responses(0) = resp
        }) <<
        ("/path", {
          case (_, resp) => responses(1) = resp
        })

      client.close()
      s.stop()
      responses.toList must be(Array("1", "2").toList)
    }

    "should support returning a chunked response" in {
      val s = new HttpServer(serverPort) {
        override def makeResponse(request: HttpRequest, buffer: StringBuilder, status: HttpResponseStatus, keepAlive: Boolean): List[AnyRef] = {
          val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status)
          response.setChunked(true)
          response.setHeader(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8")
          response.setHeader(HttpHeaders.Names.TRANSFER_ENCODING, "chunked")
          val resultString = buffer.toString()
          def takeChunk(chunks: List[HttpChunk], rest: String): List[HttpChunk] = {
            if (rest.length == 0) {
              new DefaultHttpChunkTrailer :: chunks
            } else {
              val chars = math.min(1024, rest.length)
              val chunkStr = rest.take(chars)
              val bytes = new DynamicChannelBuffer(chunkStr.getBytes.length)
              bytes.setBytes(0, chunkStr.getBytes, 0, chunkStr.getBytes.length)
              bytes.setIndex(0, chunkStr.getBytes.length) // make sure something to read
              takeChunk(new DefaultHttpChunk(bytes) :: chunks, rest.drop(chars))
            }
          }
          response :: (takeChunk(Nil, resultString).reverse)
        }
      }.start {
        case (Left(request), buffer: StringBuilder) =>
          (1 to 100000) foreach {
            i => buffer.append(i.toString).append(" ")
          }
          HttpResponseStatus.OK
        case _ => HttpResponseStatus.OK
      }

      val counter = new AtomicInteger
      val client = new HttpClient("localhost", serverPort) {
        override def onChunk(chunk: HttpChunk) {
          counter.incrementAndGet
        }
      } <<
        ("/path", {
          case (_, _) =>
        })

      client.close()
      s.stop()
      counter.get must be(577)
    }
  }
  type ? = this.type
}
