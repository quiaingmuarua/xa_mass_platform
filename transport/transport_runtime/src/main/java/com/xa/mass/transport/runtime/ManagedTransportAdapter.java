package com.xa.mass.transport.runtime;

/**
 * Lifecycle handle for an embedded transport adapter owned by runtime
 * composition.
 */
public interface ManagedTransportAdapter {

    void start();

    void stop();

    boolean isRunning();
}
