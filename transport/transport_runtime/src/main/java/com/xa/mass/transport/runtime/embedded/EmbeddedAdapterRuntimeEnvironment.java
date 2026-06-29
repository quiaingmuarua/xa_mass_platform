package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.transport.lease.TransportEndpointLeaseStore;
import com.xa.mass.transport.runtime.TransportResultIngressQueue;
import com.xa.mass.transport.runtime.delivery.TransportDispatchQueue;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;

import java.util.Objects;

/**
 * Shared infrastructure dependencies for embedded adapter runtimes.
 */
public final class EmbeddedAdapterRuntimeEnvironment {

    private final TransportDispatchQueue dispatchQueue;
    private final TransportResultIngressQueue resultQueue;
    private final TransportEndpointLeaseStore endpointLeaseStore;
    private final CurrentSessionDisconnectSink currentSessionDisconnectSink;
    private final RuntimeTaskExecutor executor;

    public EmbeddedAdapterRuntimeEnvironment(TransportDispatchQueue dispatchQueue,
                                             TransportResultIngressQueue resultQueue,
                                             TransportEndpointLeaseStore endpointLeaseStore,
                                             CurrentSessionDisconnectSink currentSessionDisconnectSink,
                                             RuntimeTaskExecutor executor) {
        this.dispatchQueue = Objects.requireNonNull(dispatchQueue, "dispatchQueue");
        this.resultQueue = Objects.requireNonNull(resultQueue, "resultQueue");
        this.endpointLeaseStore = Objects.requireNonNull(endpointLeaseStore, "endpointLeaseStore");
        this.currentSessionDisconnectSink = currentSessionDisconnectSink != null
                ? currentSessionDisconnectSink
                : CurrentSessionDisconnectSink.NOOP;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public TransportDispatchQueue dispatchQueue() {
        return dispatchQueue;
    }

    public TransportResultIngressQueue resultQueue() {
        return resultQueue;
    }

    public TransportEndpointLeaseStore endpointLeaseStore() {
        return endpointLeaseStore;
    }

    public CurrentSessionDisconnectSink currentSessionDisconnectSink() {
        return currentSessionDisconnectSink;
    }

    public RuntimeTaskExecutor executor() {
        return executor;
    }

}
