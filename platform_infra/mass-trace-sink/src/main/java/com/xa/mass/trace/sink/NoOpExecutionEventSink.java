package com.xa.mass.trace.sink;

import com.xa.mass.trace.api.ExecutionEvent;
import com.xa.mass.trace.api.ExecutionEventSink;

/**
 * No-op implementation of {@link ExecutionEventSink}.
 *
 * <p>Used as the default when no concrete sink bean is configured. All events
 * are silently discarded.</p>
 */
public final class NoOpExecutionEventSink implements ExecutionEventSink {

    @Override
    public void emit(ExecutionEvent event) {
        // intentionally empty
    }
}
