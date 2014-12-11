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

import org.slf4j.LoggerFactory
import org.jboss.netty.handler.codec.http.{HttpResponse, HttpRequest, HttpChunk}

/**
 * Various interfaces and handlers for dealing with sinking request & response data.
 *
 * @author Eric Bowman
 * @since 2011-03-29 13:16
 */

/**Defines the outbound source type: either the reference server, or shadow. */
object SourceType extends Enumeration {
  type SourceType = Value
  val Reference, Shadow = Value
}

/**Defines the outbound stream direction: either request (downstream), or response (upstream). */
object DataType extends Enumeration {
  type DataType = Value
  val Request, Response = Value
}

/**Interface for retrieving a data sink to handle a single request. */
trait DataSinkFactory {
  def dataSink(id: Int): DataSink
}

/**
 * Interface for sinking parts of the request as they become available.
 */
trait DataSink {

  import SourceType._

  def sinkRequest(sourceType: SourceType, message: HttpRequest)

  def sinkResponse(sourceType: SourceType, message: HttpResponse)

  def sinkRequestChunk(sourceType: SourceType, chunk: HttpChunk)

  def sinkResponseChunk(sourceType: SourceType, chunk: HttpChunk)
}

/**
 * Singleton null sink if capture==none
 */
object NullSink extends DataSink {

  import SourceType._

  def sinkResponseChunk(sourceType: SourceType, chunk: HttpChunk) {}

  def sinkRequestChunk(sourceType: SourceType, chunk: HttpChunk) {}

  def sinkResponse(sourceType: SourceType, message: HttpResponse) {}

  def sinkRequest(sourceType: SourceType, message: HttpRequest) {}
}

object ChainingLogSink {
  val log = LoggerFactory.getLogger(classOf[ChainingLogSink])
}

class ChainingLogSink(chained: Option[DataSink]) extends DataSink {

  import SourceType._
  import ChainingLogSink.log

  @volatile var referenceRequest: HttpRequest = _
  @volatile var referenceResponse: HttpResponse = _
  @volatile var shadowRequest: HttpRequest = _
  @volatile var shadowResponse: HttpResponse = _

  def sinkResponseChunk(sourceType: SourceType, chunk: HttpChunk) {
    chained.map(_.sinkResponseChunk(sourceType, chunk))
  }

  def sinkRequestChunk(sourceType: SourceType, chunk: HttpChunk) {
    chained.map(_.sinkRequestChunk(sourceType, chunk))
  }

  def sinkResponse(sourceType: SourceType, message: HttpResponse) {
    if (sourceType == Reference) {
      referenceResponse = message
    } else {
      shadowResponse = message
    }
    logIfDone()
    chained.map(_.sinkResponse(sourceType, message))
  }

  def sinkRequest(sourceType: SourceType, message: HttpRequest) {
    if (sourceType == Reference) {
      referenceRequest = message
    } else {
      shadowRequest = message
    }
    logIfDone()
    chained.map(_.sinkRequest(sourceType, message))
  }

  def logIfDone() {
    if (referenceRequest != null && referenceResponse != null && shadowRequest != null && shadowResponse != null) {
      log.info(referenceRequest.getUri + "\t" + referenceRequest.getMethod + "\t" +
        referenceResponse.getStatus + "\t" + shadowResponse.getStatus + "\t")
    }
  }
}


