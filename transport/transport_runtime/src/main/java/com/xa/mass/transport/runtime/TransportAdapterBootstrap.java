package com.xa.mass.transport.runtime;

/**
 * Adapter-owned bootstrap entry that assembles runtime-facing transport
 * contribution without leaking adapter implementation details into SDK
 * composition.
 */
public interface TransportAdapterBootstrap {

    void contribute(TransportAdapterBootstrapContext context);

    /**
     * Optional registration metadata for pre-start adapter-id resolution.
     *
     * <p>Bootstraps that expose worker-facing transport identities should
     * provide this so embedded runtimes can resolve registration input before
     * the live runtime registry is assembled.
     */
    default TransportAdapterDescriptor descriptor() {
        return null;
    }
}
