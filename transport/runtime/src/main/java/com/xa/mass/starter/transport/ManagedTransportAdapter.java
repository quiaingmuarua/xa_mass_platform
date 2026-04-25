package com.xa.mass.starter.transport;

/**
 * Lifecycle handle for an embedded transport adapter owned by runtime
 * composition.
 */
public interface ManagedTransportAdapter {

    void start();

    void stop();

    boolean isRunning();
}
