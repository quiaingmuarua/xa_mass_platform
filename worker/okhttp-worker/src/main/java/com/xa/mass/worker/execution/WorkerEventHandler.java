package com.xa.mass.worker.execution;

@FunctionalInterface
public interface WorkerEventHandler<P, R> {

    R execute(P parameters) throws Exception;
}
