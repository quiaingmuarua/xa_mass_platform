package com.xa.mass.transport.channel;

import com.xa.mass.transport.routing.RoutingEnvelope;

/**
 * Consumer side of worker result ingress used by local buffers and inbox pumps.
 */
@FunctionalInterface
public interface TransportResultIngressHandler {

    TransportResultIngressOutcome handle(RoutingEnvelope envelope);
}
