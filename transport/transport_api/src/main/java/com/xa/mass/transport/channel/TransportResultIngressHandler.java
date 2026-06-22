package com.xa.mass.transport.channel;

/**
 * Consumer side of worker result ingress used by local buffers and inbox pumps.
 */
@FunctionalInterface
public interface TransportResultIngressHandler {

    TransportResultIngressOutcome handle(ResultIngressEntry entry);
}
