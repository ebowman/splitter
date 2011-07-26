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

package tomtom.splitter.config

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import java.io.StringReader

@RunWith(classOf[JUnitRunner])
class ConfigSpec extends Spec with ShouldMatchers with ConfigParser {
  type ? = this.type

  describe("The config parser") {
    val testConfig = """
      myInt = 7
      myBool = false
      myString = "now is the time"
      myStringNewline = "now\nis the\ntime"
      myStringTab = "now\tis the time"

      # comment, preceded & followed by blank line

      mySubConfig {
        mySubInt = 8
        mySubBool = true
        mySubString = "for all good \"men\" to come to the aid of their country"
        mySubSubConfig {
          foo = "bar"
        }
      }
    """

    val config = new Config {
      val configMap = parse(new StringReader(testConfig))
    }

    config.int("myInt") should(be(7))
    config.intOpt("myInt") should(be(Some(7)))
    config.intOpt("yourInt") should(be(None))

    config.bool("myBool") should(be(false))
    config.boolOpt("myBool") should(be(Some(false)))
    config.boolOpt("yourBool") should(be(None))

    config.string("myString") should(be("now is the time"))
    config.stringOpt("myString") should(be(Some("now is the time")))
    config.stringOpt("yourString") should(be(None))

    config.string("myStringNewline") should be(
      """|now
         |is the
         |time""".stripMargin)

    config.string("myStringTab") should be("now\tis the time")

    val subConfig = config.config("mySubConfig")
    config.configOpt("mySubConfig") should(be(Some(subConfig)))
    config.configOpt("yourSubConfig") should(be(None))

    subConfig.int("mySubInt") should(be(8))
    subConfig.string("mySubString") should(be("for all good \"men\" to come to the aid of their country"))
    subConfig.bool("mySubBool") should(be(true))

    val subsub = subConfig.config("mySubSubConfig")
    subsub.string("foo") should(be("bar"))

    config.string("mySubConfig.mySubSubConfig.foo") should(be("bar"))
  }
}