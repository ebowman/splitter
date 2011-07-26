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

import org.jboss.netty._
import handler.codec.http.HttpResponseDecoder
import channel.{Channels, ChannelPipeline, ChannelPipelineFactory}

/**
 * Document me.
 *
 * @author Eric Bowman
 * @since 2011-03-28 09:34
 */

/**
 * The pipeline factory gets instantiated once per cached connection. This is the channel from splitter
 * to the reference server; downstream is what we write to, upstream is what we read from.
 */
class ReferenceChannelPipelineFactory extends ChannelPipelineFactory {

  def getPipeline: ChannelPipeline = {
    val pipeline = Channels.pipeline

    implicit val sourceType = SourceType.Reference

    pipeline.addLast("reference_request_encoder", new SinkingHttpRequestEncoder) // downstream (1)

    pipeline.addLast("reference_coupler", new ReferenceCouplingHandler) // upstream   (2)
    pipeline.addLast("reference_response_decoder", new HttpResponseDecoder) // upstream   (3)
    pipeline.addLast("reference_response_handler", new ReferenceResponseHandler) // upstream   (4)

    pipeline
  }
}

/**
 * The pipeline factory gets instantiated once per cached connection. This is the channel from splitter
 * to the shadow server; downstream is what we write to, upstream is what we read from.
 */
class ShadowChannelPipelineFactory extends ChannelPipelineFactory {

  def getPipeline: ChannelPipeline = {
    val pipeline = Channels.pipeline

    implicit val sourceType = SourceType.Shadow

    pipeline.addLast("shadow_request_encoder", new SinkingHttpRequestEncoder) // downstream (1)

    pipeline.addLast("shadow_coupler", new ShadowCouplingHandler) // upstream   (2)
    pipeline.addLast("shadow_response_decoder", new HttpResponseDecoder) // upstream   (3)
    pipeline.addLast("shadow_response_handler", new ShadowResponseHandler) // upstream   (4)

    pipeline
  }
}
