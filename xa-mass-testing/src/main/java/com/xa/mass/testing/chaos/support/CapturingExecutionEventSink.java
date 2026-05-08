package com.xa.mass.testing.chaos.support;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * In-memory {@link ExecutionEventSink} that accumulates {@link ExecutionEvent} objects
 * for assertion in chaos probes and acceptance tests.
 *
 * <p>Thread-safe. Use {@link #events()} to get a point-in-time snapshot; use
 * {@link #eventsOfType} or {@link #eventsForTask} for filtered views.
 *
 * <p>Wire into a chaos harness via {@link ChaosRuntimeHarness.Builder#traceSink(CapturingExecutionEventSink)}.
 */
public final class CapturingExecutionEventSink implements ExecutionEventSink {

    private final CopyOnWriteArrayList<ExecutionEvent> captured = new CopyOnWriteArrayList<>();

    @Override
    public void emit(ExecutionEvent event) {
        if (event != null) {
            captured.add(event);
        }
    }

    /** Snapshot of all captured events in emission order. */
    public List<ExecutionEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(captured));
    }

    /** All events of the given type. */
    public List<ExecutionEvent> eventsOfType(ExecutionEventType type) {
        return filter(e -> e.getEventType() == type);
    }

    /** All events whose identity.taskId matches. */
    public List<ExecutionEvent> eventsForTask(String taskId) {
        return filter(e -> e.getIdentity() != null && taskId.equals(e.getIdentity().taskId()));
    }

    /** All events of the given type and task. */
    public List<ExecutionEvent> eventsOfTypeForTask(ExecutionEventType type, String taskId) {
        return filter(e -> e.getEventType() == type
                && e.getIdentity() != null
                && taskId.equals(e.getIdentity().taskId()));
    }

    /** Count of captured events. */
    public int size() {
        return captured.size();
    }

    /** True if no events have been captured yet. */
    public boolean isEmpty() {
        return captured.isEmpty();
    }

    /** Clears all captured events. Useful when reusing between scenarios. */
    public void clear() {
        captured.clear();
    }

    private List<ExecutionEvent> filter(Predicate<ExecutionEvent> pred) {
        return captured.stream().filter(pred).collect(Collectors.toList());
    }
}
