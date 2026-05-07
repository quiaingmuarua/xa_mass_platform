package com.xa.mass.transport.runtime;

/**
 * Adapter-owned side channel for sending raw transport messages to a concrete
 * online worker endpoint without leaking adapter delivery DTOs into SDK
 * composition.
 *
 * <p>This is an operational/debug surface only. It must not become product
 * lifecycle truth or a second task-control mainline.
 */
public interface RawWorkerMessageChannel {

    /**
     * Returns the concrete adapter identity served by this raw side channel.
     */
    String adapterId();

    /**
     * Returns whether this channel can confidently route a raw message to the
     * given transport route under the current runtime state.
     */
    boolean supportsAdapterRoute(String routeKey, String requestedAdapterId);

    /**
     * Sends a raw transport payload to a concrete route-addressed endpoint.
     */
    void sendToAdapterRoute(String routeKey, String rawJson, String traceId);
}
