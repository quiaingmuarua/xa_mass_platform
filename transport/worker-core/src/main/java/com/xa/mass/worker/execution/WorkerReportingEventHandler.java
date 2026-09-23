package com.xa.mass.worker.execution;

/** A normal synchronous execution result plus an optional retained outcome reporter. */
@FunctionalInterface
public interface WorkerReportingEventHandler<P> extends WorkerEventHandler<P> {
    @Override
    String execute(P parameters, WorkerOutcomeReporter reporter) throws Exception;

    @Override
    default String execute(P parameters) throws Exception {
        return execute(parameters, WorkerOutcomeReporter.UNAVAILABLE);
    }
}
