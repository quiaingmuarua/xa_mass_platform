package com.xa.mass.transport.runtime;

import com.xa.mass.transport.routing.RoutingEnvelope;

/**
 * Narrow adapter-facing result ingress sink.
 */
@FunctionalInterface
public interface AdapterResultIngressSink {

    boolean ingest(RoutingEnvelope envelope);
}
