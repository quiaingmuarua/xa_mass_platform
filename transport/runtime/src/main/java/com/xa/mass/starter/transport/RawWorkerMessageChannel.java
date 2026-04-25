package com.xa.mass.starter.transport;

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
     * Returns whether this channel can confidently route a raw message to the
     * given worker under the current runtime state.
     */
    boolean supports(String workerId);

    /**
     * Sends a raw transport payload to a concrete worker endpoint.
     */
    void send(String workerId, String rawJson, String traceId);
}
