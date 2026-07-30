package com.xa.mass.worker.execution;

@FunctionalInterface
public interface WorkerEventHandler<P> {

    String execute(P parameters) throws Exception;
}
