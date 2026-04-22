package com.xa.mass.starter.transport;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.transport.WorkerEndpointRegistry;

/**
 * Adapter context used when the embedded runtime asks a transport factory to
 * create its inbound server.
 */
public final class TransportServerFactoryContext {

    private final DispatchRuntimeContext dispatcherContext;
    private final WorkerEndpointRegistry workerEndpointRegistry;
    private final int port;
    private final String endpointPath;

    public TransportServerFactoryContext(DispatchRuntimeContext dispatcherContext,
                                         WorkerEndpointRegistry workerEndpointRegistry,
                                         int port,
                                         String endpointPath) {
        this.dispatcherContext = dispatcherContext;
        this.workerEndpointRegistry = workerEndpointRegistry;
        this.port = port;
        this.endpointPath = endpointPath;
    }

    public DispatchRuntimeContext getDispatcherContext() {
        return dispatcherContext;
    }

    public WorkerEndpointRegistry getWorkerEndpointRegistry() {
        return workerEndpointRegistry;
    }

    public int getPort() {
        return port;
    }

    public String getEndpointPath() {
        return endpointPath;
    }
}
