package com.xa.mass.client.worker.handler;

@FunctionalInterface
public interface WorkerEventHandler {
    WorkerResult handle(WorkerInvocation invocation) throws Exception;
}
