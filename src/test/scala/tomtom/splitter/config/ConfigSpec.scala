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

import java.io.{File, StringReader}

import org.scalatest.{FlatSpec, Matchers}

class ConfigSpec extends FlatSpec with Matchers with ConfigParser {
  type ? = this.type

  behavior of "the config parser"

  it should "behave as expected" in {
    val testConfig =
      """
      myInt = 7
      myBool = false
      myString = "now is the time"
      myStringNewline = "now\nis the\ntime"
      myStringTab = "now\tis the time"
      myFile = "a.txt"

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

    config.int("myInt") should be(7)
    config.intOpt("myInt") should be(Some(7))
    config.intOpt("yourInt") should be(None)

    config.bool("myBool") should be(false)
    config.boolOpt("myBool") should be(Some(false))
    config.boolOpt("yourBool") should be(None)

    config.string("myString") should be("now is the time")
    config.stringOpt("myString") should be(Some("now is the time"))
    config.stringOpt("yourString") should be(None)

    config.file("myFile") should be(new File("a.txt"))
    config.file("missing file", new File("b.txt")) should be(new File("b.txt"))
    config.fileOpt("missing file") should be(None)
    config.fileOpt("myFile") should be(Some(new File("a.txt")))

    config.string("myStringNewline") should be(
      """|now
         |is the
         |time""".stripMargin)

    config.string("myStringTab") should be("now\tis the time")

    val subConfig = config.config("mySubConfig")
    config.configOpt("mySubConfig") should be(Some(subConfig))
    config.configOpt("yourSubConfig") should be(None)

    subConfig.int("mySubInt") should be(8)
    subConfig.int("mySubIntMissing", 7) should be(7)
    subConfig.string("mySubString") should be("for all good \"men\" to come to the aid of their country")
    subConfig.string("missing string", "foo") should be("foo")
    subConfig.bool("mySubBool") should be(true)
    subConfig.bool("missing bool", default = false) should be(false)

    val subsub = subConfig.config("mySubSubConfig")
    subsub.string("foo") should be("bar")

    config.string("mySubConfig.mySubSubConfig.foo") should be("bar")

    a[RuntimeException] shouldBe thrownBy(config.config(""))
    a[RuntimeException] shouldBe thrownBy(config.config("no such config.bar"))
    a[RuntimeException] shouldBe thrownBy(config.bool("no such boolean"))
  }

  it should "load a global config from various places" in {
    Config.loadResource("/test.config")
    Config.config.config("audit").string("level") should be("warn")
    Config.loadFile(new File("src/test/resources/test.config"))
    Config.config.config("audit").string("level") should be("warn")
    Config.loadString(
      """
        |audit {
        |  level = "info"
        |}
      """.stripMargin)
    Config.config.config("audit").string("level") should be("info")
  }

  it should "fail predictably when not initialized" in {
    Config._config.set(null)
    a[RuntimeException] should be thrownBy Config.config
  }

  it should "fail predictably when parsing garbage" in {
    a[RuntimeException] should be thrownBy new ConfigParser {}.parse(new StringReader("blah"))
  }

  it should "missing config should behave with configOpt" in {
    Config.loadResource("/test.config")
    Config.config.configOpt("no such config") should be(None)
  }

  it should "behave correctly with default values for config values in non-existing subconfigs" in {
    Config.loadResource("/test.config")
    Config.config.int("no such config.myInt", 7) should be(7)
  }
}
