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

import org.jboss.netty.handler.codec.http.{DefaultHttpRequest, HttpRequest}
import util.matching.Regex
import util.matching.Regex.Match
import util.parsing.combinator.RegexParsers
import io.Source
import java.io.File

case class Rewriter(matcher: Regex, target: String) {
  def rewrite(uri: String): Option[String] = {
    matcher.findFirstMatchIn(uri).map {
      case m: Match =>
        var tmp = target
        for (group <- 1 to m.groupCount) {
          tmp = tmp.replace("$" + group, m.group(group))
        }
        tmp
    }
  }

  override def equals(rewriter: Any): Boolean = {
    rewriter match {
      case r: Rewriter =>
        target == r.target && matcher.toString == r.matcher.toString
      case _ => false
    }
  }
}

trait RewriteParser extends RegexParsers {

  override def skipWhitespace = false

  protected val allMethods = Set("GET", "POST", "PUT", "DELETE", "HEAD")
  protected val method = "GET" | "POST" | "PUT" | "DELETE" | "HEAD"
  protected val wildcard = "*"
  protected val methods = rep1sep(method, ",") | wildcard
  protected val token = """[^\t\n]+""".r
  protected val pattern = token ^^ {
    new Regex(_)
  }
  protected val rewrite = token

  protected val rewriter: Parser[Rewriter] = pattern ~ "\t" ~ rewrite ^^ {
    case ptrn ~ _ ~ rewrte => Rewriter(ptrn, rewrte)
  }

  protected val rule: Parser[(Set[String], Rewriter)] = methods ~ "\t" ~ rewriter ^^ {
    case mthods ~ _ ~ rewrter =>
      mthods match {
        case "*" => (allMethods, rewrter)
        case list: List[_] => (list.map((x: Any) => x.toString).toSet, rewrter)
      }
  }

  protected val rules: Parser[List[(Set[String], Rewriter)]] = repsep(rule, "\n")

  def parse(config: File) = {
    val toParse = Source.fromFile(config).getLines().filter(
      line => line.trim.length > 0 && !line.startsWith("#")).mkString("\n")
    parseAll(rules, toParse)
  }
}

trait RequestMapperModule {

  val shadowHostname: Option[String]
  val rewriteConfig: Option[File]

  // Useful if you don't feel like writing a rewrite method
  def identity[T](x: HttpRequest): Option[HttpRequest] = Some(x)

  object RequestMapper extends RewriteParser {
    val rewriteRules: Option[Map[String, List[Rewriter]]] = rewriteConfig.map {
      reader =>
        parse(reader) match {
          case f@NoSuccess(_, _) =>
            sys.error("Could not parse rewriteConfig: " + f)
          case Success(rools, _) =>
            val flatter: List[(String, Rewriter)] = for {
              (methods, rule) <- rools
              method <- methods
            } yield method -> rule
            flatter.groupBy(_._1).map(x => x._1 -> x._2.map(_._2))
        }
    }

    def rewrite(request: HttpRequest): Option[HttpRequest] = {
      for {
        ruleMap <- rewriteRules
        rewriters <- ruleMap.get(request.getMethod.getName)
        rewritten <- rewriters.view.map(_.rewrite(request.getUri)).find(_.isDefined).flatten.headOption
      } yield {
        val copied = copy(request)
        copied.setUri(rewritten)
        shadowHostname.foreach(copied.setHeader("Host", _))
        copied
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
        copy.headers.set(name, request.getHeaders(name))
      }

      copy
    }
  }

}
