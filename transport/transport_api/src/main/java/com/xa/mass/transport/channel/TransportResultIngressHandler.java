package com.xa.mass.transport.channel;

/**
 * Consumer side of worker result ingress used by local buffers and starter-owned drains.
 */
@FunctionalInterface
public interface TransportResultIngressHandler {

    void handle(ResultIngressEntry entry);
}
