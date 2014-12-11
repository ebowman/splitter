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

import ch.qos.logback.classic.Level
import ch.qos.logback.core.FileAppender
import org.slf4j.{Logger, LoggerFactory}
import tomtom.splitter.config.Config

object Logging {
  def config(config: Config) {

    // override the default logback configuration
    config.stringOpt("config") match {
      case Some(path) => System.setProperty("logback.configurationFile", path)
      case None =>
    }

    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
    if (root != null && root.isInstanceOf[ch.qos.logback.classic.Logger]) {

      val rootLogger = root.asInstanceOf[ch.qos.logback.classic.Logger]

      if (!config.bool("console", default = true)) {
        rootLogger.detachAppender("CONSOLE")
      }

      /**
       * Set file=default to use what is in the logback config, leave it out
       * in order to disable file logging, or set a filename explicitly.
       */
      val fileAppender = rootLogger.getAppender("FILE").asInstanceOf[FileAppender[_]]

      config.stringOpt("file") match {
        case None => rootLogger.detachAppender("FILE")
        case Some("default") =>
        case Some(file) =>
          fileAppender.setFile(file)
      }

      /**
       * truncate the log file?
       */
      val truncate = config.bool("truncate", default = false)
      if (truncate && truncate == fileAppender.isAppend) {
        fileAppender.setAppend(!truncate)
      }

      fileAppender.start() // pick up any changes

      /**
       * Override the log level as needed
       */
      config.stringOpt("level").collect {
        case "trace" => Level.TRACE
        case "debug" => Level.DEBUG
        case "info" => Level.INFO
        case "warn" => Level.WARN
        case "error" => Level.ERROR
      }.foreach (rootLogger.setLevel)
    }
  }
}
