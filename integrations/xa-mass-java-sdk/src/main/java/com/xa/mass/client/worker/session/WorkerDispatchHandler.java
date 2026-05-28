package com.xa.mass.client.worker.session;

@FunctionalInterface
public interface WorkerDispatchHandler {
    WorkerResult handle(DispatchContext dispatch) throws Exception;
}
