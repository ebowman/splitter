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

import java.io.File

import org.jboss.netty.handler.codec.http.{DefaultHttpRequest, HttpMethod, HttpVersion}
import org.scalatest.{Matchers, WordSpec}

/**
  * Verifies basic behavior of RequestMapper.
  *
  * @author Eric Bowman
  * @since 2011-04-12 19:15:22
  */

class RequestsTest extends WordSpec with Matchers with RequestMapperModule {

  override val shadowHostname = Some("my.hostname")
  override val rewriteConfig = Some(new File("ofbiz.config"))

  def fromUri(method: HttpMethod)(uri: String) = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri)

  def post(uri: String) = fromUri(HttpMethod.POST)(uri)

  def get(uri: String) = fromUri(HttpMethod.GET)(uri)

  def put(uri: String) = fromUri(HttpMethod.PUT)(uri)

  def delete(uri: String) = fromUri(HttpMethod.DELETE)(uri)

  import RequestMapper._

  "The RequestMapper" should {

    "rewrite a CreateUser user POST" in {
      assert(rewrite(post("/buenos-aires-ws/services/wfe/users/<q_f>")).map(_.getUri) === Some("/ttuser/atom/users/<q_f>"))
    }
    "rewrite a CreateUser user PUT" in {
      assert(rewrite(put("/buenos-aires-ws/services/wfe/users/<q_f>")).map(_.getUri) === Some("/ttuser/atom/users/<q_f>"))
    }
    "not rewrite a CreateUser user GET" in {
      assert(rewrite(get("/buenos-aires-ws/services/wfe/users/<q_f>")).map(_.getUri) === None)
    }

    "rewrite a GetUserBasicProfile GET" in {
      assert(rewrite(get("/buenos-aires-ws/services/wfe/users/<uid>/profiles/basic<q_f>")).map(_.getUri) === Some("/ttuser/atom/users/<uid>/profiles/basic<q_f>"))
    }

    "not rewrite a GetUserBasicProfile POST" in {
      assert(rewrite(post("/buenos-aires-ws/services/wfe/users/<uid>/profiles/basic<q_f>")).map(_.getUri) === None)
    }

    "rewrite any ManageUser method" in {
      assert(rewrite(get("/buenos-aires-ws/tomtommain/UserService.rpc")).map(_.getUri) === Some("/ttuser/gwtrpc/UserService.rpc"))
      assert(rewrite(post("/buenos-aires-ws/tomtommain/UserService.rpc")).map(_.getUri) === Some("/ttuser/gwtrpc/UserService.rpc"))
      assert(rewrite(put("/buenos-aires-ws/tomtommain/UserService.rpc")).map(_.getUri) === Some("/ttuser/gwtrpc/UserService.rpc"))
    }

    "rewrite legacy manage user POST" in {
      assert(rewrite(post("/proxy.php")).map(_.getUri) === Some("/ttuser/control/legacy"))
    }

    "not rewrite legacy manage user GET or PUT" in {
      assert(rewrite(get("/proxy.php")).map(_.getUri) === None)
      assert(rewrite(put("/proxy.php")).map(_.getUri) === None)
    }
  }
  type ? = this.type
}
