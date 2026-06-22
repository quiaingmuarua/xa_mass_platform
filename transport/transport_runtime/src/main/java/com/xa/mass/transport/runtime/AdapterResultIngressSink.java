package com.xa.mass.transport.runtime;

import com.xa.mass.transport.channel.ResultIngressEntry;

/**
 * Narrow adapter-facing result ingress sink.
 */
@FunctionalInterface
public interface AdapterResultIngressSink {

    boolean ingest(ResultIngressEntry entry);
}
