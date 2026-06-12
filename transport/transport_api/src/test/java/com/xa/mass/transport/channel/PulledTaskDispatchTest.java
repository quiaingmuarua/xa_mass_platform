package com.xa.mass.transport.channel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PulledTaskDispatchTest {

    @Test
    void exposesOnlyWorkerPullFields() {
        Set<String> fields = Arrays.stream(PulledTaskDispatch.class.getDeclaredFields())
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of(
                "taskId",
                "messageId",
                "eventCode",
                "input",
                "sharedConfig",
                "attemptId",
                "attemptNo",
                "retryCount",
                "batchId"
        ), fields);
        assertFalse(fields.contains("routeKey"));
        assertFalse(fields.contains("transportPayload"));
        assertFalse(fields.contains("workerId"));
    }

    @Test
    void normalizesMapsAndCounters() {
        PulledTaskDispatch item = new PulledTaskDispatch(
                " task-1 ",
                " msg-1 ",
                " event-1 ",
                Map.of("target", "a"),
                null,
                " attempt-1 ",
                -1,
                -2,
                " batch-1 "
        );

        assertEquals("task-1", item.getTaskId());
        assertEquals("msg-1", item.getMessageId());
        assertEquals("event-1", item.getEventCode());
        assertEquals(Map.of("target", "a"), item.getInput());
        assertEquals(Map.of(), item.getSharedConfig());
        assertEquals("attempt-1", item.getAttemptId());
        assertEquals(0, item.getAttemptNo());
        assertEquals(0, item.getRetryCount());
        assertEquals("batch-1", item.getBatchId());
    }

    @Test
    void requiresTaskAndMessageIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new PulledTaskDispatch(
                " ",
                "msg-1",
                null,
                Map.of(),
                Map.of(),
                null,
                0,
                0,
                null
        ));
        assertThrows(IllegalArgumentException.class, () -> new PulledTaskDispatch(
                "task-1",
                " ",
                null,
                Map.of(),
                Map.of(),
                null,
                0,
                0,
                null
        ));
    }
}
