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

import collection.immutable.Map
import util.parsing.combinator.JavaTokenParsers
import java.io._

trait Config {

  val configMap: Map[String, Any]

  private def getConfig(path: String): Option[(String, Config)] = {
    def recurse(list: List[String], cfg: Config): Option[(String, Config)] = {
      list match {
        case Nil => sys.error("Could not parse an empty path")
        case name :: Nil => Some((name, cfg))
        case name :: tail => cfg.configOpt(name) match {
          case Some(c) => recurse(tail, c)
          case None => None
        }
      }
    }
    recurse(path.split("\\.").toList, this)
  }

  def configOpt(name: String): Option[Config] = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap.get(n).map(_.asInstanceOf[Config])
    case None => None
  }

  def config(name: String): Config = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap(n).asInstanceOf[Config]
    case None => sys.error("No configuration property named " + name)
  }

  def boolOpt(name: String): Option[Boolean] = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap.get(n).map(_.asInstanceOf[Boolean])
    case None => None
  }

  def bool(name: String): Boolean = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap(n).asInstanceOf[Boolean]
    case None => sys.error("No configuration property named " + name)
  }

  def bool(name: String, default: Boolean): Boolean = boolOpt(name).getOrElse(default)

  def intOpt(name: String): Option[Int] = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap.get(n).map(_.asInstanceOf[Int])
    case None => None
  }

  def int(name: String): Int = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap(n).asInstanceOf[Int]
    case None => sys.error("No configuration property named " + name)
  }

  def int(name: String, default: Int): Int = intOpt(name).getOrElse(default)

  def stringOpt(name: String):Option[String] = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap.get(n).map(_.asInstanceOf[String])
    case None => None
  }

  def string(name: String):String = getConfig(name) match {
    case Some((n, cfg)) => cfg.configMap(n).asInstanceOf[String]
    case None => sys.error("No configuration property named " + name)
  }

  def string(name: String, default: String): String = stringOpt(name).getOrElse(default)

  def fileOpt(name: String) = stringOpt(name).map(new File(_))

  def file(name: String) = new File(string(name))

  def file(name: String, default: File): File = fileOpt(name).getOrElse(default)
}

object Config {

  private var inputSource: Option[() => InputStream] = None

  def load(inputStream: () => InputStream) = this.inputSource = Some(inputStream)

  def loadResource(str: String) = load(() => getClass.getResourceAsStream(str))

  def loadFile(file: File) = load(() => new FileInputStream(file))

  def loadString(str: String) = load(() => new ByteArrayInputStream(str.getBytes("UTF-8")))

  lazy val config = new Config {
    val configMap = inputSource match {
      case None => sys.error("You must load configuration before accessing it.")
      case Some(fn) =>
        val iStream = fn()
        try {
          new ConfigParser() {}.parse(new InputStreamReader(iStream, "UTF-8"))
        } finally {
          iStream.close()
        }
    }
  }
}

trait ConfigParser extends JavaTokenParsers {
  // overriding, so support \"
  // see https://issues.scala-lang.org/browse/SI-4138
  // we only support \n, \t, and \"
  override def stringLiteral: Parser[String] =
    ("\"" + """([^"\p{Cntrl}\\]|\\[\\/nt"]|\\u[a-fA-F0-9]{4})*""" + "\"").r

  def string = (ident <~ "=") ~ stringLiteral ^^ {
    case name ~ value => Some((name -> value.drop(1).take(value.size - 2).
      replaceAll("\\\\n", "\n").replaceAll("\\\\t", "\t").replaceAll("\\\\\"", "\"")))
  }

  def int = (ident <~ "=") ~ wholeNumber ^^ {
    case name ~ value => Some((name -> value.toInt))
  }

  def bool = (ident <~ "=") ~ """true|false""".r ^^ {
    case name ~ value => Some((name, value.toBoolean))
  }

  def comment = """^#[^\n]*""".r ^^ { case _ => None: Option[(String, Any)]}

  def property = string | int | bool | comment

  def subconfig: Parser[_] = (ident <~ "{") ~ (rep(property | subconfig) <~ "}") ^^ {
    case name ~ (list: List[Option[(String, Any)]]) => Some((name -> new Config {
      override val configMap = Map(list.flatten: _*)
    }))
  }

  def config = rep(property | subconfig) ^^ {
    case (seq: List[Option[(String, Any)]]) => Map(seq.flatten: _*)
  }

  def parse(input: Reader): Map[String, Any] = parseAll(config, input) match {
    case Success(map, _) => map
    case f@_ => sys.error("Could not parse: " + f)
  }
}