package com.xa.mass.transport.channel;

/**
 * Transport-owned producer side of worker result ingress.
 */
@FunctionalInterface
public interface TransportResultIngressChannel {

    boolean ingest(ResultIngressEntry entry);
}
