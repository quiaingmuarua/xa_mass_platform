package com.xa.mass.transport.channel;

import com.xa.mass.transport.model.TransportResultIngressEnvelope;

/**
 * Transport-owned producer side of worker result ingress.
 */
@FunctionalInterface
public interface TransportResultIngressChannel {

    boolean ingest(TransportResultIngressEnvelope envelope);
}
