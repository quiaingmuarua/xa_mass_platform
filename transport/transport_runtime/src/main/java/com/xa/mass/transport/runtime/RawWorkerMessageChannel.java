package com.xa.mass.transport.runtime;

/**
 * Adapter-owned side channel for sending raw transport messages to a concrete
 * online worker without leaking adapter delivery DTOs or endpoint/session
 * views into SDK composition.
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
     * Sends a raw transport payload to the adapter-local session for the
     * already selected worker.
     */
    boolean sendToWorker(String workerId, String rawJson, String traceId);
}
