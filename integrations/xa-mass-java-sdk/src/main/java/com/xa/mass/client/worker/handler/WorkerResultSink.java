package com.xa.mass.client.worker.handler;

@FunctionalInterface
public interface WorkerResultSink {
    void submit(DispatchContext dispatch, WorkerResult result) throws Exception;
}
