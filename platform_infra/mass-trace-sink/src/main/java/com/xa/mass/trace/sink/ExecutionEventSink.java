package com.xa.mass.trace.sink;

/**
 * Sink for execution lifecycle events.
 *
 * <p>Implementations must be thread-safe. Hot-path callers should use
 * {@link #emitIfEnabled(ExecutionEvent)} so that a disabled sink avoids object allocation.
 */
public interface ExecutionEventSink {

    void emit(ExecutionEvent event);

    default void emitIfEnabled(ExecutionEvent event) {
        emit(event);
    }
}
