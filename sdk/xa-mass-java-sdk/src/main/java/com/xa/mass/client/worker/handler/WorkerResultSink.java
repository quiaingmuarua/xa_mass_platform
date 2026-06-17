package com.xa.mass.client.worker.handler;

import com.xa.mass.client.worker.ResultCorrelationRef;

@FunctionalInterface
public interface WorkerResultSink {
    void submit(ResultCorrelationRef resultCorrelationRef, WorkerResult result) throws Exception;
}
