package com.xa.mass.sdk.worker;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerActionTest {

    @Test
    void exposesOnlySdkWorkerPullFields() {
        Set<String> fields = Arrays.stream(WorkerAction.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "actionId",
                "replyRef",
                "eventCode",
                "body",
                "sharedConfig"
        ), fields);
        assertFalse(fields.contains("routeKey"));
        assertFalse(fields.contains("transportPayload"));
        assertFalse(fields.contains("TransportPacket"));
        assertFalse(fields.contains("workerId"));
    }

    @Test
    void normalizesMapsAndCounters() {
        WorkerAction item = new WorkerAction(
                " action-1 ",
                " corr-1 ",
                " event-1 ",
                " {\"target\":\"a\"} ",
                null
        );

        assertEquals("action-1", item.getActionId());
        assertEquals("corr-1", item.getReplyRef());
        assertEquals("event-1", item.getEventCode());
        assertEquals(" {\"target\":\"a\"} ", item.getBody());
        assertEquals(Map.of(), item.getSharedConfig());
    }

    @Test
    void requiresReplyRef() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerAction(
                "action-1",
                " ",
                "event-1",
                "{}",
                Map.of()
        ));
    }
}
