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

import org.jboss.netty.handler.codec.http.{DefaultHttpRequest, HttpMethod, HttpRequest}
import org.slf4j.LoggerFactory

trait RequestMapperModule {

  val shadowHostname: Option[String]
  val referenceHostname: Option[String]

  // Useful if you don't feel like writing a rewrite method
  def identity[T](x: HttpRequest): Option[HttpRequest] = Some(x)

  object RequestMapper {
    val CreateUser = "/buenos-aires-ws/services/wfe/users/(.*)".r
    val GetUserBasicProfile = "/buenos-aires-ws/services/wfe/users/(.*?)/profiles/basic(.*)".r
    val ManageUser = "/buenos-aires-ws/tomtommain/UserService.rpc(.*)".r
    val ManageUserLegacy = "/proxy.php(.*)".r

    val methods = Map(
      CreateUser -> Set(HttpMethod.POST, HttpMethod.PUT),
      GetUserBasicProfile -> Set(HttpMethod.GET),
      ManageUserLegacy -> Set(HttpMethod.POST))

    val rewrites = Map(
      CreateUser -> "/ttuser/atom/users/%s",
      GetUserBasicProfile -> "/ttuser/atom/users/%s/profiles/basic%s",
      ManageUser -> "/ttuser/gwtrpc/UserService.rpc%s",
      ManageUserLegacy -> "/ttuser/control/legacy%s"
    )

    val log = LoggerFactory.getLogger(getClass)

    def rewrite(request: HttpRequest): Option[HttpRequest] = {
      (request.getUri match {
        // note the order matters here. GetUserBasicProfile BEFORE CreateUser
        case GetUserBasicProfile(a, b) =>
          // can't put an if guard, or it will fall through to CreateUser
          if (methods(GetUserBasicProfile).contains(request.getMethod)) {
            Some(rewrites(GetUserBasicProfile).format(a, b))
          } else {
            None
          }
        case CreateUser(id) if methods(CreateUser).contains(request.getMethod) =>
          Some(rewrites(CreateUser) format id)
        case ManageUser(q) =>
          Some(rewrites(ManageUser) format q)
        case ManageUserLegacy(q) if methods(ManageUserLegacy).contains(request.getMethod) =>
          Some(rewrites(ManageUserLegacy) format q)
        case _ => None
      }) match {
        case Some(rewritten) =>
          val copied = copy(request)
          copied.setUri(rewritten)
          shadowHostname.foreach(copied.setHeader("Host", _))
          Some(copied)
        case None => None
      }
    }

    def copy(request: HttpRequest): HttpRequest = {
      val copy = new DefaultHttpRequest(request.getProtocolVersion, request.getMethod, request.getUri)

      if (request.isChunked) {
        copy.setChunked(true)
      } else {
        copy.setContent(request.getContent)
      }

      import collection.JavaConverters._
      for (name <- request.getHeaderNames.asScala) {
        copy.setHeader(name, request.getHeaders(name))
      }

      copy
    }
  }

}
