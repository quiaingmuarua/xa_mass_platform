package com.xa.mass.worker.execution;

@FunctionalInterface
public interface WorkerEventParameterResolver<P> {

    P resolve(String payload) throws Exception;
}
