package com.xa.mass.testing.chaos.support;

import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventType;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fluent trace event assertions for chaos probes and acceptance tests.
 *
 * <p>All assertion methods call {@link ChaosSupport#require} on failure, so they throw with
 * descriptive messages that appear in chaos runner output and CI logs.
 *
 * <p>Example — verify a task's full terminal lifecycle is in the trace:
 * <pre>{@code
 * TraceEventAssertions.of(sink)
 *     .forTask(task.getTid())
 *     .requireEventType(ExecutionEventType.TASK_STATUS_TRANSITION)
 *     .requireEventType(ExecutionEventType.TASK_TERMINAL_CLOSED)
 *     .requireTerminalReason("ALL_MESSAGES_FAILED");
 * }</pre>
 */
public final class TraceEventAssertions {

    private final CapturingExecutionEventSink sink;
    private String taskId;

    private TraceEventAssertions(CapturingExecutionEventSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public static TraceEventAssertions of(CapturingExecutionEventSink sink) {
        return new TraceEventAssertions(sink);
    }

    /** Scope subsequent assertions to a specific task. */
    public TraceEventAssertions forTask(String taskId) {
        this.taskId = taskId;
        return this;
    }

    /** Assert that at least one event of the given type was captured (scoped by taskId if set). */
    public TraceEventAssertions requireEventType(ExecutionEventType type) {
        List<ExecutionEvent> matching = taskId != null
                ? sink.eventsOfTypeForTask(type, taskId)
                : sink.eventsOfType(type);
        ChaosSupport.require(!matching.isEmpty(),
                "expected at least one " + type + " event" + taskScope() + " but none found; "
                        + "all captured types: " + summarizeTypes());
        return this;
    }

    /** Assert that at least one of the given event types was captured. */
    public TraceEventAssertions requireAnyEventType(ExecutionEventType... types) {
        ChaosSupport.require(types != null && types.length > 0,
                "at least one event type must be supplied");
        for (ExecutionEventType type : types) {
            List<ExecutionEvent> matching = taskId != null
                    ? sink.eventsOfTypeForTask(type, taskId)
                    : sink.eventsOfType(type);
            if (!matching.isEmpty()) {
                return this;
            }
        }
        String expectedTypes = java.util.Arrays.stream(types)
                .map(ExecutionEventType::name)
                .collect(Collectors.joining(", "));
        ChaosSupport.require(false,
                "expected at least one of [" + expectedTypes + "]" + taskScope()
                        + " but none found; all captured types: " + summarizeTypes());
        return this;
    }

    /** Assert the exact count of events of the given type (scoped by taskId if set). */
    public TraceEventAssertions requireEventTypeCount(ExecutionEventType type, int expectedCount) {
        List<ExecutionEvent> matching = taskId != null
                ? sink.eventsOfTypeForTask(type, taskId)
                : sink.eventsOfType(type);
        ChaosSupport.require(matching.size() == expectedCount,
                "expected exactly " + expectedCount + " " + type + " event(s)" + taskScope()
                        + " but found " + matching.size());
        return this;
    }

    /**
     * Assert a TASK_TERMINAL_CLOSED event was emitted and its {@code transition.reason}
     * (or attrs["terminalReason"]) matches the expected value.
     */
    public TraceEventAssertions requireTerminalReason(String expectedReason) {
        List<ExecutionEvent> terminals = taskId != null
                ? sink.eventsOfTypeForTask(ExecutionEventType.TASK_TERMINAL_CLOSED, taskId)
                : sink.eventsOfType(ExecutionEventType.TASK_TERMINAL_CLOSED);
        ChaosSupport.require(!terminals.isEmpty(),
                "expected TASK_TERMINAL_CLOSED event" + taskScope() + " but none found");
        boolean found = terminals.stream().anyMatch(e -> terminalReasonMatches(e, expectedReason));
        ChaosSupport.require(found,
                "expected TASK_TERMINAL_CLOSED with terminalReason=" + expectedReason + taskScope()
                        + "; found reasons: " + terminals.stream()
                        .map(TraceEventAssertions::extractTerminalReason)
                        .collect(Collectors.joining(", ")));
        return this;
    }

    /**
     * Assert that TASK_WORK_STATUS_TRANSITION events for the task contain at least {@code minCount}
     * events where {@code transition.dst} equals the expected destination status.
     */
    public TraceEventAssertions requireMessageStatusTransitions(String dstStatus, int minCount) {
        List<ExecutionEvent> transitions = taskId != null
                ? sink.eventsOfTypeForTask(ExecutionEventType.TASK_WORK_STATUS_TRANSITION, taskId)
                : sink.eventsOfType(ExecutionEventType.TASK_WORK_STATUS_TRANSITION);
        long matched = transitions.stream()
                .filter(e -> e.getTransition() != null && dstStatus.equals(e.getTransition().dst()))
                .count();
        ChaosSupport.require(matched >= minCount,
                "expected >= " + minCount + " TASK_WORK_STATUS_TRANSITION → " + dstStatus
                        + taskScope() + " but found " + matched);
        return this;
    }

    /**
     * Assert that at least one CALLBACK_ACCEPTED event was emitted for the given message.
     */
    public TraceEventAssertions requireCallbackAccepted(String messageId) {
        List<ExecutionEvent> callbacks = sink.eventsOfType(ExecutionEventType.CALLBACK_ACCEPTED);
        boolean found = callbacks.stream().anyMatch(e ->
                e.getIdentity() != null && messageId.equals(e.getIdentity().messageId())
                        && (taskId == null || taskId.equals(e.getIdentity().taskId())));
        ChaosSupport.require(found,
                "expected CALLBACK_ACCEPTED for messageId=" + messageId + taskScope() + " but none found");
        return this;
    }

    /**
     * Assert total number of captured events is at least {@code minTotal}. Useful as a sanity
     * check that the sink was wired correctly and the engine emitted events at all.
     */
    public TraceEventAssertions requireMinTotalEvents(int minTotal) {
        ChaosSupport.require(sink.size() >= minTotal,
                "expected at least " + minTotal + " total captured trace events but found " + sink.size());
        return this;
    }

    /** Produces a summary map suitable for inclusion in a chaos report JSON. */
    public java.util.Map<String, Object> summaryMap(String taskId) {
        List<ExecutionEvent> forTask = sink.eventsForTask(taskId);
        java.util.Map<String, Long> byType = new java.util.LinkedHashMap<>();
        for (ExecutionEvent e : forTask) {
            byType.merge(e.getEventType().name(), 1L, Long::sum);
        }
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("totalForTask", forTask.size());
        map.put("totalCaptured", sink.size());
        map.put("byType", byType);
        return java.util.Map.copyOf(map);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String taskScope() {
        return taskId != null ? " for task=" + taskId : "";
    }

    private String summarizeTypes() {
        return sink.events().stream()
                .map(e -> e.getEventType().name())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static boolean terminalReasonMatches(ExecutionEvent e, String expected) {
        // Check transition.reason first (set by TraceEventLogger.taskTerminalClosed)
        if (e.getTransition() != null && expected.equals(e.getTransition().reason())) {
            return true;
        }
        // Fallback: attrs["terminalReason"]
        if (e.getAttrs() != null) {
            Object attrVal = e.getAttrs().get("terminalReason");
            return expected.equals(String.valueOf(attrVal));
        }
        return false;
    }

    private static String extractTerminalReason(ExecutionEvent e) {
        if (e.getTransition() != null && e.getTransition().reason() != null) {
            return e.getTransition().reason();
        }
        if (e.getAttrs() != null) {
            Object v = e.getAttrs().get("terminalReason");
            if (v != null) return String.valueOf(v);
        }
        return "<none>";
    }
}
