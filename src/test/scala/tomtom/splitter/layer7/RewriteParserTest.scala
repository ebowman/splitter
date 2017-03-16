package tomtom.splitter.layer7

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

import org.scalatest.{Matchers, WordSpec}

/**
  * Verifies basic behavior of RequestMapper.
  *
  * @author Eric Bowman
  * @since 2011-06-27 13:05:40
  */

class RewriteParserTest extends WordSpec with Matchers with RewriteParser {

  "The RewriteParser" should {

    "parse all the methods" in {
      allMethods.foreach(parseAll(method, _).successful should equal(true))
    }
    "parse a wildcard" in {
      parseAll(wildcard, "*").successful should equal(true)
      parseAll(wildcard, "foo").successful should equal(false)
    }
    "parse the methods in several cases" in {
      parseAll(methods, "GET,POST,DELETE").successful should equal(true)
      parseAll(methods, "*").successful should equal(true)
      parseAll(methods, "GET,*").successful should equal(false)
    }
    "parse a pattern correctly" in {
      // bit of a gotcha here -- Pattern.equals isn't implemented, so these patterns are not ==
      parseAll(pattern, "/buenos-aires-ws/services/wfe/users/(.*)").get.toString should
        equal("/buenos-aires-ws/services/wfe/users/(.*)".r.toString())
    }
    "parse a rewriter correctly" in {
      parseAll(rewriter, "/buenos-aires-ws/services/wfe/users/(.*)\t/ttuser/atom/users/$1").get should equal {
        Rewriter("/buenos-aires-ws/services/wfe/users/(.*)".r, "/ttuser/atom/users/$1")
      }
    }
    "parse a simple rule" in {
      parseAll(rule, "GET\t/buenos-aires-ws/services/wfe/users/(.*)\t/ttuser/atom/users/$1").get should equal {
        (Set("GET"), Rewriter("/buenos-aires-ws/services/wfe/users/(.*)".r, "/ttuser/atom/users/$1"))
      }
    }
    "parse a wildcard rule" in {
      parseAll(rule, "*\t/buenos-aires-ws/services/wfe/users/(.*)\t/ttuser/atom/users/$1").get should equal {
        (allMethods, Rewriter("/buenos-aires-ws/services/wfe/users/(.*)".r, "/ttuser/atom/users/$1"))
      }
    }

    "not parse a malformed rule" in {
      parseAll(rule, "POST").successful should equal(false)
    }

    "parse no rules successfully" in {
      parseAll(rules, "").successful should equal(true)
    }
    "parse a single-instance set of rules" in {
      parseAll(rules, "*\t/buenos-aires-ws/services/wfe/users/(.*)\t/ttuser/atom/users/$1").get should equal {
        List((allMethods, Rewriter("/buenos-aires-ws/services/wfe/users/(.*)".r, "/ttuser/atom/users/$1")))
      }
    }

    "parse multiple rules" in {
      parseAll(rules,
        "GET\t/foo/(.*)/\t/bar/$1\n" +
          "POST\t/bar/(.*)/\t/foo/$1\n" +
          "GET,POST\t/who/(.*)\t/why/$1").get should equal {
        List(
          (Set("GET"), Rewriter("/foo/(.*)/".r, "/bar/$1")),
          (Set("POST"), Rewriter("/bar/(.*)/".r, "/foo/$1")),
          (Set("GET", "POST"), Rewriter("/who/(.*)".r, "/why/$1"))
        )
      }
    }

  }
  type ? = this.type
}
