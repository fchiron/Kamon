/* =========================================================================================
 * Copyright © 2013-2017 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package kamon.trace

import java.net.{URLDecoder, URLEncoder}
import java.nio.ByteBuffer

import kamon.Kamon
import kamon.context.generated.binary.span.{Span => ColferSpan}
import kamon.context.{Codecs, Context, TextMap}
import kamon.trace.SpanContext.SamplingDecision


object SpanCodec {

  class B3Single extends Codecs.ForEntry[TextMap] {
    import B3Single.Header

    override def encode(context: Context): TextMap = {
      val span = context.get(Span.ContextKey)
      val carrier = TextMap.Default()

      // * b3: {x-b3-traceid}-{x-b3-spanid}-{if x-b3-flags 'd' else x-b3-sampled}-{x-b3-parentspanid}

      if(span.nonEmpty()) {
        val buffer = new StringBuffer()
        val spanContext = span.context()

        val traceId = urlEncode(spanContext.traceID.string)
        val spanId = urlEncode(spanContext.spanID.string)

        buffer.append(traceId).append("-").append(spanId)

        encodeSamplingDecision(spanContext.samplingDecision)
          .foreach(samplingDecision => buffer.append("-").append(samplingDecision))

        if(spanContext.parentID != IdentityProvider.NoIdentifier)
          buffer.append("-").append(urlEncode(spanContext.parentID.string))

        carrier.put(Header.B3, buffer.toString)
      }
      carrier
    }

    override def decode(carrier: TextMap, context: Context): Context = {
      import B3Single.Syntax

      carrier.get(Header.B3).map { header =>
        val identityProvider = Kamon.tracer.identityProvider

        val (trace, span, sample, parentSpan) = header.splitToTuple("-")

        val traceID = trace.map(id => identityProvider.traceIdGenerator().from(urlDecode(id)))
          .getOrElse(IdentityProvider.NoIdentifier)

        val spanID = span.map(id => identityProvider.spanIdGenerator().from(urlDecode(id)))
          .getOrElse(IdentityProvider.NoIdentifier)

        if (traceID != IdentityProvider.NoIdentifier && spanID != IdentityProvider.NoIdentifier) {
          val parentID = parentSpan
            .map(id => identityProvider.spanIdGenerator().from(urlDecode(id)))
            .getOrElse(IdentityProvider.NoIdentifier)

          val flags = carrier.get(Header.Flags)

          val samplingDecision = flags.orElse(sample)  match {
            case Some(sampled) if sampled == "1" => SamplingDecision.Sample
            case Some(sampled) if sampled == "0" => SamplingDecision.DoNotSample
            case _ => SamplingDecision.Unknown
          }

          context.withKey(Span.ContextKey, Span.Remote(SpanContext(traceID, spanID, parentID, samplingDecision)))

        } else context
      }.getOrElse(context)
    }

    private def encodeSamplingDecision(samplingDecision: SamplingDecision): Option[String] = samplingDecision match {
      case SamplingDecision.Sample      => Some("1")
      case SamplingDecision.DoNotSample => Some("0")
      case SamplingDecision.Unknown     => None
    }

    private def urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
    private def urlDecode(s: String): String = URLDecoder.decode(s, "UTF-8")
  }

  object B3Single {
    object Header {
      val B3 = "B3"
      val Flags = "X-B3-Flags"
    }

    implicit class Syntax(val s: String) extends AnyVal {
      def splitToTuple(regex: String): (Option[String], Option[String], Option[String], Option[String]) = {
        s.split(regex) match {
          case Array(str1, str2, str3, str4) => (Option(str1), Option(str2), Option(str3), Option(str4))
          case Array(str1, str2, str3) => (Option(str1), Option(str2), Option(str3), None)
          case Array(str1, str2) => (Option(str1), Option(str2), None, None)
          case Array(str1) => (Option(str1), None, None, None)
        }
      }
    }

    def apply(): B3Single =
      new B3Single()
  }



  class B3 extends Codecs.ForEntry[TextMap] {
    import B3.Headers

    override def encode(context: Context): TextMap = {
      val span = context.get(Span.ContextKey)
      val carrier = TextMap.Default()

      if(span.nonEmpty()) {
        val spanContext = span.context()
        carrier.put(Headers.TraceIdentifier, urlEncode(spanContext.traceID.string))
        carrier.put(Headers.SpanIdentifier, urlEncode(spanContext.spanID.string))

        if(spanContext.parentID != IdentityProvider.NoIdentifier)
          carrier.put(Headers.ParentSpanIdentifier, urlEncode(spanContext.parentID.string))

        encodeSamplingDecision(spanContext.samplingDecision).foreach { samplingDecision =>
          carrier.put(Headers.Sampled, samplingDecision)
        }
      }

      carrier
    }

    override def decode(carrier: TextMap, context: Context): Context = {
      val identityProvider = Kamon.tracer.identityProvider
      val traceID = carrier.get(Headers.TraceIdentifier)
        .map(id => identityProvider.traceIdGenerator().from(urlDecode(id)))
        .getOrElse(IdentityProvider.NoIdentifier)

      val spanID = carrier.get(Headers.SpanIdentifier)
        .map(id => identityProvider.spanIdGenerator().from(urlDecode(id)))
        .getOrElse(IdentityProvider.NoIdentifier)

      if(traceID != IdentityProvider.NoIdentifier && spanID != IdentityProvider.NoIdentifier) {
        val parentID = carrier.get(Headers.ParentSpanIdentifier)
          .map(id => identityProvider.spanIdGenerator().from(urlDecode(id)))
          .getOrElse(IdentityProvider.NoIdentifier)

        val flags = carrier.get(Headers.Flags)

        val samplingDecision = flags.orElse(carrier.get(Headers.Sampled)) match {
          case Some(sampled) if sampled == "1" => SamplingDecision.Sample
          case Some(sampled) if sampled == "0" => SamplingDecision.DoNotSample
          case _ => SamplingDecision.Unknown
        }

        context.withKey(Span.ContextKey, Span.Remote(SpanContext(traceID, spanID, parentID, samplingDecision)))

      } else context
    }

    private def encodeSamplingDecision(samplingDecision: SamplingDecision): Option[String] = samplingDecision match {
      case SamplingDecision.Sample      => Some("1")
      case SamplingDecision.DoNotSample => Some("0")
      case SamplingDecision.Unknown     => None
    }

    private def urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
    private def urlDecode(s: String): String = URLDecoder.decode(s, "UTF-8")
  }

  object B3 {

    def apply(): B3 =
      new B3()

    object Headers {
      val TraceIdentifier = "X-B3-TraceId"
      val ParentSpanIdentifier = "X-B3-ParentSpanId"
      val SpanIdentifier = "X-B3-SpanId"
      val Sampled = "X-B3-Sampled"
      val Flags = "X-B3-Flags"
    }
  }


  class Colfer extends Codecs.ForEntry[ByteBuffer] {
    val emptyBuffer = ByteBuffer.allocate(0)

    override def encode(context: Context): ByteBuffer = {
      val span = context.get(Span.ContextKey)
      if(span.nonEmpty()) {
        val marshalBuffer = Colfer.codecBuffer.get()
        val colferSpan = new ColferSpan()
        val spanContext = span.context()

        colferSpan.setTraceID(spanContext.traceID.bytes)
        colferSpan.setSpanID(spanContext.spanID.bytes)
        colferSpan.setParentID(spanContext.parentID.bytes)
        colferSpan.setSamplingDecision(samplingDecisionToByte(spanContext.samplingDecision))

        val marshalledSize = colferSpan.marshal(marshalBuffer, 0)
        val buffer = ByteBuffer.allocate(marshalledSize)
        buffer.put(marshalBuffer, 0, marshalledSize)
        buffer

      } else emptyBuffer
    }

    override def decode(carrier: ByteBuffer, context: Context): Context = {
      carrier.clear()

      if(carrier.capacity() == 0)
        context
      else {
        val identityProvider = Kamon.tracer.identityProvider
        val colferSpan = new ColferSpan()
        colferSpan.unmarshal(carrier.array(), 0)

        val spanContext = SpanContext(
          traceID = identityProvider.traceIdGenerator().from(colferSpan.traceID),
          spanID = identityProvider.spanIdGenerator().from(colferSpan.spanID),
          parentID = identityProvider.spanIdGenerator().from(colferSpan.parentID),
          samplingDecision = byteToSamplingDecision(colferSpan.samplingDecision)
        )

        context.withKey(Span.ContextKey, Span.Remote(spanContext))
      }
    }


    private def samplingDecisionToByte(samplingDecision: SamplingDecision): Byte = samplingDecision match {
      case SamplingDecision.Sample      => 1
      case SamplingDecision.DoNotSample => 2
      case SamplingDecision.Unknown     => 3
    }

    private def byteToSamplingDecision(byte: Byte): SamplingDecision = byte match {
      case 1 => SamplingDecision.Sample
      case 2 => SamplingDecision.DoNotSample
      case _ => SamplingDecision.Unknown
    }
  }

  object Colfer {
    private val codecBuffer = new ThreadLocal[Array[Byte]] {
      override def initialValue(): Array[Byte] = Array.ofDim[Byte](256)
    }
  }
}
