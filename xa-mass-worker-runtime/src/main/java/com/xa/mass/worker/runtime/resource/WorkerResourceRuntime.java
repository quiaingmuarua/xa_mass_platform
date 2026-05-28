package com.xa.mass.worker.runtime.resource;

/**
 * Worker resource declaration and dispatch-gate mutation surface.
 */
public interface WorkerResourceRuntime extends WorkerResourceQueryRuntime,
        WorkerResourceDeclarationRuntime,
        WorkerNodeBindingRuntime {
}
