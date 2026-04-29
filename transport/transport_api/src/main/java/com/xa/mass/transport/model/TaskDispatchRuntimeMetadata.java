package com.xa.mass.transport.model;

/**
 * Runtime-only dispatch metadata that transport uses for routing and internal
 * result correlation. This is not the worker wire payload contract.
 */
public record TaskDispatchRuntimeMetadata(String attemptId,
                                          String workerId,
                                          String workerContextId,
                                          String batchId) {
}
