package com.xa.mass.runtime.worker;

/**
 * Worker resource declaration and dispatch-gate mutation surface.
 */
public interface WorkerResourceRuntime extends WorkerResourceQueryRuntime,
        WorkerResourceDeclarationRuntime,
        WorkerNodeBindingRuntime {
}
