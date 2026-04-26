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
     * given worker under the current runtime state.
     */
    default boolean supports(String workerId, String workerAdapterId) {
        return supportsAdapter(workerAdapterId);
    }

    /**
     * Returns whether the requested adapter id resolves to this side-channel.
     */
    default boolean supportsAdapter(String workerAdapterId) {
        return adapterId() != null
                && workerAdapterId != null
                && adapterId().equalsIgnoreCase(workerAdapterId.trim());
    }

    /**
     * Returns whether a worker id can be used for endpoint lookup.
     */
    default boolean hasWorkerId(String workerId) {
        return workerId != null && !workerId.isBlank();
    }

    /**
     * Sends a raw transport payload to a concrete worker endpoint.
     */
    void send(String workerId, String rawJson, String traceId);
}
