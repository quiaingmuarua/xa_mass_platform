package com.xa.mass.trace.sink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class NoopExecutionEventSinkTest {

    @Test
    void noop_doesNotThrowForAnyInput() {
        NoopExecutionEventSink noop = new NoopExecutionEventSink();
        assertDoesNotThrow(() -> noop.emit(null));
        assertDoesNotThrow(() -> noop.emitIfEnabled(null));
    }
}
