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

import java.io.{StringWriter, PrintWriter}

object Exceptions {
  def stackTrace(t: Throwable): String = {
    try {
      val writer = new StringWriter
      t.printStackTrace(new PrintWriter(writer))
      writer.toString
    } catch {
      case e: Exception =>
        "Oops, blew an exception dealing with " + t + " -> " + e
    }
  }
}
