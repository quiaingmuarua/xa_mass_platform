package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.handler.WorkerInvocation;
import com.xa.mass.client.worker.handler.WorkerResult;

@FunctionalInterface
public interface WorkerDispatchHandler {
    WorkerResult handle(WorkerInvocation invocation) throws Exception;
}
