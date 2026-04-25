package com.xa.mass.starter.transport;

/**
 * Adapter-owned bootstrap entry that assembles runtime-facing transport
 * contribution without leaking adapter implementation details into SDK
 * composition.
 */
public interface TransportAdapterBootstrap<T> {

    TransportAdapterContribution create(TransportAdapterBootstrapContext<T> context);
}
