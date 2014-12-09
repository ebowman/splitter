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

import java.util.concurrent.ExecutorService
import org.jboss.netty.handler.codec.http.{HttpHeaders, QueryStringDecoder, HttpResponse, HttpResponseStatus, HttpRequest}

/**
 * Document me.
 *
 * @author Eric Bowman
 * @since 2011-04-12 19:31
 */

/**
 * Document me.
 *
 * @author Eric Bowman
 * @since 2011-04-07 09:19
 */

class CommandableServer(name: String, port: Int)(implicit executor: ExecutorService) extends HttpServer(port)(executor) {

  val Sleep = """sleep (\d+)""".r
  val Status = """status (\d+)""".r
  val Host = """host""".r

  override def makeResponse(request: HttpRequest,
                            buffer: StringBuilder,
                            status: HttpResponseStatus,
                            keepAlive: Boolean): List[AnyRef] = {
    val responseBits = super.makeResponse(request, buffer, status, keepAlive)
    if (request.headers.get("X-Request-Id") != null) {
      responseBits.head.asInstanceOf[HttpResponse].headers.set(
        "X-Request-Id", request.headers.get("X-Request-Id"))
    }
    responseBits
  }

  def start() {
    start {
      case (Left(request), buffer: StringBuilder) =>
        import collection.JavaConverters._
        val decoder = new QueryStringDecoder(request.getUri)
        val params = decoder.getParameters.asScala
        params.get(name) match {
          case Some(commands) => commands.asScala.toList match {
            case "ok" :: Nil =>
              buffer.append(name + " ok")
              HttpResponseStatus.OK
            case Sleep(ms) :: Nil =>
              buffer.append(name + " sleep " + ms)
              Thread.sleep(ms.toLong)
              HttpResponseStatus.OK
            case Status(code) :: Nil =>
              buffer.append(name + " status " + code)
              HttpResponseStatus.valueOf(code.toInt)
            case Host() :: Nil =>
              buffer.append("HOST=" + request.headers.get(HttpHeaders.Names.HOST))
              HttpResponseStatus.OK
            case _ =>
              buffer.append(name + " could not parse " + request.getUri)
              HttpResponseStatus.OK
          }
          case None =>
            buffer.append(name + " no command found in " + request.getUri)
            HttpResponseStatus.OK
        }
      case _ => HttpResponseStatus.OK
    }
  }
}
