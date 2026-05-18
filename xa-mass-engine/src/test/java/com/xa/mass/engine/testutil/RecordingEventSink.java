package com.xa.mass.engine.testutil;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class RecordingEventSink implements ExecutionEventSink {

    private final List<ExecutionEvent> events = new ArrayList<>();

    @Override
    public void emit(ExecutionEvent event) {
        events.add(event);
    }

    public List<ExecutionEvent> events() {
        return List.copyOf(events);
    }

    public List<ExecutionEvent> eventsOfType(ExecutionEventType type) {
        return events.stream()
                .filter(event -> event.getEventType() == type)
                .toList();
    }

    public boolean hasEvent(ExecutionEventType type) {
        return events.stream().anyMatch(event -> event.getEventType() == type);
    }

    public Optional<ExecutionEvent> firstEventOfType(ExecutionEventType type) {
        return events.stream()
                .filter(event -> event.getEventType() == type)
                .findFirst();
    }

    public void assertHasEvent(ExecutionEventType type) {
        assertTrue(hasEvent(type), "Expected event type " + type);
    }

    public void assertHasEvent(ExecutionEventType type, String attrKey, Object attrValue) {
        assertTrue(events.stream().anyMatch(event ->
                        event.getEventType() == type
                                && attrValue.equals(event.getAttrs().get(attrKey))),
                "Expected event " + type + " with " + attrKey + "=" + attrValue);
    }
}
