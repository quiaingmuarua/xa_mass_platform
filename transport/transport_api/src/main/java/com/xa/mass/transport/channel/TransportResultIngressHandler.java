package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TransportResultIngressEnvelope;

/**
 * Consumer side of worker result ingress used by local buffers and inbox pumps.
 */
@FunctionalInterface
public interface TransportResultIngressHandler {

    TransportResultIngressOutcome handle(TransportResultIngressEnvelope envelope);
}
