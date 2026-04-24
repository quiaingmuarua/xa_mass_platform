package com.xa.mass.starter.transport;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.transport.WorkerEndpointRegistry;

/**
 * Adapter context used when the embedded runtime asks a transport factory to
 * create its inbound server.
 */
public final class TransportServerFactoryContext {

    private final DispatchRuntimeContext dispatcherContext;
    private final WorkerEndpointRegistry endpointRegistry;
    private final int port;
    private final String endpointPath;

    public TransportServerFactoryContext(DispatchRuntimeContext dispatcherContext,
                                         WorkerEndpointRegistry endpointRegistry,
                                         int port,
                                         String endpointPath) {
        this.dispatcherContext = dispatcherContext;
        this.endpointRegistry = endpointRegistry;
        this.port = port;
        this.endpointPath = endpointPath;
    }

    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }

    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    public int getPort() {
        return port;
    }

    public String getEndpointPath() {
        return endpointPath;
    }
}
