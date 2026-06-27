package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.runtime.TransportAdapterDescriptor;
import com.xa.mass.transport.runtime.TransportBinding;

/**
 * Embedded adapter runtime with adapter-owned start/close lifecycle.
 */
public interface EmbeddedTransportAdapterRuntime extends AutoCloseable {

    TransportAdapterDescriptor descriptor();

    TransportBinding binding();

    void start();

    boolean isRunning();

    @Override
    void close();
}
