package com.xa.mass.trace.api;

/**
 * Contract for accepting execution trace events.
 *
 * <p>Callers must treat {@link #emit} as fire-and-forget. Implementations are
 * required to be non-blocking on the hot path — the caller must never block
 * waiting for IO. Dropped events are acceptable; blocking callers is not.</p>
 *
 * <p>This interface is the only surface other modules ({@code xa-mass-engine},
 * {@code transport}) should depend on. Implementations live in this module and
 * are wired by the assembly layer.</p>
 */
public interface ExecutionEventSink {

    /**
     * Emit an execution event. Must return immediately — must not block on IO
     * or queue backpressure. Events may be silently dropped if the sink is
     * overloaded; callers must not rely on delivery guarantees.
     *
     * @param event the event to emit; must not be null
     */
    void emit(ExecutionEvent event);
}
