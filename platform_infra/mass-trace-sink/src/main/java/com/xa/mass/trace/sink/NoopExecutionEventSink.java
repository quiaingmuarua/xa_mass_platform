package com.xa.mass.trace.sink;

/**
 * No-operation sink used when tracing is disabled.
 */
public final class NoopExecutionEventSink implements ExecutionEventSink {

    @Override
    public void emit(ExecutionEvent event) {
        // intentionally empty
    }

    @Override
    public void emitIfEnabled(ExecutionEvent event) {
        // intentionally empty
    }
}
