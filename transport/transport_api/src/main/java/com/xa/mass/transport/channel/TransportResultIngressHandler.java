package com.xa.mass.transport.channel;

/**
 * Consumer side of worker result ingress used by local buffers and queue pumps.
 */
@FunctionalInterface
public interface TransportResultIngressHandler {

    void handle(ResultIngressEntry entry);
}
