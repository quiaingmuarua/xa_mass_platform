package com.xa.mass.starter.transport;

/**
 * Factory seam for assembling the set of worker transport bindings used by an
 * embedded runtime.
 */
public interface WorkerTransportRuntimeFactory {

    TransportRuntimeRegistry create(WorkerTransportRuntimeFactoryContext context);
}
