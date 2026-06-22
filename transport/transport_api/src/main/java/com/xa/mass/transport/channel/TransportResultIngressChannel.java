package com.xa.mass.transport.channel;

import com.xa.mass.transport.routing.RoutingEnvelope;

/**
 * Transport-owned producer side of worker result ingress.
 */
@FunctionalInterface
public interface TransportResultIngressChannel {

    boolean ingest(RoutingEnvelope envelope);
}
