package com.xa.mass.client.worker.handler;

@FunctionalInterface
public interface WorkerEventHandler {
    WorkerResult handle(DispatchContext dispatch) throws Exception;
}
