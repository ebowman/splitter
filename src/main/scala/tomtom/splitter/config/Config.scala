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

import java.util.concurrent.atomic.AtomicReference

import collection.immutable.Map
import util.parsing.combinator.JavaTokenParsers
import java.io._

trait Config {

  val configMap: Map[String, Any]

  private def getConfig(path: String): (String, Config) = {
    def recurse(list: List[String], cfg: Config): (String, Config) = {
      list match {
        case Nil => sys.error("Could not parse an empty path")
        case name :: Nil => (name, cfg)
        case name :: tail => cfg.configOpt(name).map(c => recurse(tail, c)).getOrElse(sys.error(s"Could not find config $name within $path"))
      }
    }
    recurse(path.split("\\.").toList.filterNot(_.isEmpty), this)
  }

  def configOpt(name: String): Option[Config] = {
    val (n, cfg) = getConfig(name)
    cfg.configMap.get(n).map(_.asInstanceOf[Config])
  }

  def config(name: String): Config = {
    val (n, cfg) = getConfig(name)
    cfg.configMap(n).asInstanceOf[Config]
  }

  def boolOpt(name: String): Option[Boolean] = {
    val (n, cfg) = getConfig(name)
    cfg.configMap.get(n).map(_.asInstanceOf[Boolean])
  }

  def bool(name: String): Boolean = {
    val (n, cfg) = getConfig(name)
    cfg.configMap(n).asInstanceOf[Boolean]
  }

  def bool(name: String, default: Boolean): Boolean = boolOpt(name).getOrElse(default)

  def intOpt(name: String): Option[Int] = {
    val (n, cfg) = getConfig(name)
    cfg.configMap.get(n).map(_.asInstanceOf[Int])
  }

  def int(name: String): Int = {
    val (n, cfg) = getConfig(name)
    cfg.configMap(n).asInstanceOf[Int]
  }

  def int(name: String, default: Int): Int = intOpt(name).getOrElse(default)

  def stringOpt(name: String): Option[String] = {
    val (n, cfg) = getConfig(name)
    cfg.configMap.get(n).map(_.asInstanceOf[String])
  }

  def string(name: String): String = {
    val (n, cfg) = getConfig(name)
    cfg.configMap(n).asInstanceOf[String]
  }

  def string(name: String, default: String): String = stringOpt(name).getOrElse(default)

  def fileOpt(name: String) = stringOpt(name).map(new File(_))

  def file(name: String) = new File(string(name))

  def file(name: String, default: File): File = fileOpt(name).getOrElse(default)
}

object Config {

  private var inputSource: Option[() => InputStream] = None
  private[config] val _config = new AtomicReference[Config]

  def load(inputStream: InputStream) = {
    try {
      _config.set(new Config {
        val configMap = new ConfigParser() {}.parse(new InputStreamReader(inputStream, "UTF-8"))
      })
    } finally {
      inputStream.close()
    }
  }

  def loadResource(str: String) = load(getClass.getResourceAsStream(str))

  def loadFile(file: File) = load(new FileInputStream(file))

  def loadString(str: String) = load(new ByteArrayInputStream(str.getBytes("UTF-8")))

  def config = Option(_config.get()).getOrElse(sys.error("You must load configuration before accessing it"))
}

trait ConfigParser extends JavaTokenParsers {

  def string: Parser[(String, String)] = (ident <~ "=") ~ stringLiteral ^^ {
    case name ~ value => name -> value.drop(1).take(value.size - 2).
      replaceAll("\\\\n", "\n").replaceAll("\\\\t", "\t").replaceAll("\\\\\"", "\"")
  }

  def int: Parser[(String, Int)] = (ident <~ "=") ~ wholeNumber ^^ {
    case name ~ value => name -> value.toInt
  }

  def bool: Parser[(String, Boolean)] = (ident <~ "=") ~ """true|false""".r ^^ {
    case name ~ value => name -> value.toBoolean
  }

  def comment: Parser[(String, None.type)] = """^#[^\n]*""".r ^^ { case _ => "comment" -> None }

  def property: Parser[(String, Any)] = string | int | bool | comment

  def subconfig: Parser[_] = (ident <~ "{") ~ (rep(property | subconfig) <~ "}") ^^ {
    case name ~ list => name -> new Config {
      override val configMap = Map(list.collect { case (key, value) if value != None => key.toString -> value } : _*)
    }
  }

  def config = rep(property | subconfig) ^^ {
    case seq: Seq[_] => Map(seq.collect { case (key, value) => key.toString -> value}: _*)
  }

  def parse(input: Reader): Map[String, Any] = parseAll(config, input) match {
    case Success(map, _) => map
    case f@_ => sys.error("Could not parse: " + f)
  }
}
