package com.xa.mass.client.worker.handler;

import com.xa.mass.client.worker.WorkerInvocation;

@FunctionalInterface
public interface WorkerEventHandler {
    WorkerResult handle(WorkerInvocation invocation) throws Exception;
}
