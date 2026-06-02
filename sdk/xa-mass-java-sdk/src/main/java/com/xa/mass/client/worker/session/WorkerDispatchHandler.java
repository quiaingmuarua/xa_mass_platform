package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.handler.DispatchContext;
import com.xa.mass.client.worker.handler.WorkerResult;

@FunctionalInterface
public interface WorkerDispatchHandler {
    WorkerResult handle(DispatchContext dispatch) throws Exception;
}
