package com.xa.mass.transport.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskDispatchItemTest {

    @Test
    void unwrapsSdkJsonPayloadForTransportConsumers() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "type", "json",
                "data", Map.of("url", "https://example.test/page-1")
        )));

        assertEquals("https://example.test/page-1", item.getInput().get("url"));
    }

    @Test
    void unwrapsSdkTextPayloadForTransportConsumers() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "type", "text",
                "text", "hello"
        )));

        assertEquals(Map.of("text", "hello"), item.getInput());
    }

    @Test
    void keepsPlainInputsUntouchedWhenNoSdkWrapperMetadataExists() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of("target", "worker-a")));

        assertEquals(Map.of("target", "worker-a"), item.getInput());
    }

    @Test
    void carriesLatestAttemptIdentityForInternalResultCorrelation() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of("target", "worker-a")));

        assertEquals("attempt-1", item.attemptId());
        assertEquals("worker-1", item.getWorkerId());
        assertEquals("ctx-1", item.getWorkerContextId());
        assertEquals("batch-1", item.getBatchId());
    }

    @Test
    void workerLevelDispatchPayloadOmitsLegacyWorkerContextIdWhenNull() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(
                TaskDispatchContext.from(task),
                binding(Map.of("target", "worker-a"), null)
        );

        assertNull(item.getWorkerContextId());
        assertFalse(item.transportPayloadView().containsKey(TransportPacket.PAYLOAD_WORKER_CONTEXT_ID));
    }

    @Test
    void exposesDispatchFieldsDirectlyWithoutExtraWrapperObjects() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "type", "json",
                "data", Map.of("url", "https://example.test/page-1")
        )));

        assertEquals("binding.event", item.getEventCode());
        assertEquals("attempt-1", item.attemptId());
        assertEquals("worker-1", item.getWorkerId());
        assertEquals("msg-1", item.getMessageId());
        assertEquals("task-1", item.getTaskId());
        assertEquals("ctx-1", item.getWorkerContextId());
        assertEquals("batch-1", item.getBatchId());
        assertEquals("https://example.test/page-1", item.getInput().get("url"));
    }

    @Test
    void plainDataFieldWithoutJsonWrapperTypeRemainsUntouched() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "type", "custom",
                "data", Map.of("url", "https://example.test/page-1")
        )));

        assertEquals("custom", item.getInput().get("type"));
        assertEquals(Map.of("url", "https://example.test/page-1"), item.getInput().get("data"));
    }

    @Test
    void plainDataFieldWithoutTypeRemainsUntouched() {
        Task task = new Task();
        task.setTid("task-1");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "data", Map.of("url", "https://example.test/page-1"),
                "target", "worker-a"
        )));

        assertEquals(Map.of("url", "https://example.test/page-1"), item.getInput().get("data"));
        assertEquals("worker-a", item.getInput().get("target"));
    }

    @Test
    void decodedTransportPayloadReusesTrustedImmutableMaps() {
        Map<String, Object> input = Map.of("target", "worker-a");
        Map<String, Object> sharedConfig = Map.of("mode", "fast");

        TaskDispatchItem item = TaskDispatchItem.fromDecodedTransportPayload(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-1",
                "route-1",
                "worker-1",
                "ctx-1",
                "batch-1",
                input,
                sharedConfig
        );

        assertSame(input, item.getInput());
        assertSame(sharedConfig, item.getSharedConfig());
    }

    @Test
    void transportPayloadIsStableAcrossRepeatedReads() {
        TaskDispatchItem item = TaskDispatchItem.fromDecodedTransportPayload(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-1",
                "route-1",
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of("target", "worker-a"),
                Map.of("mode", "fast")
        );

        assertSame(item.transportPayloadView(), item.transportPayloadView());
    }

    @Test
    void inputAndSharedConfigDetachNestedMutableValues() {
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, Object> nestedInput = new LinkedHashMap<>();
        nestedInput.put("target", "worker-a");
        input.put("payload", nestedInput);
        Map<String, Object> sharedConfig = new LinkedHashMap<>();
        Map<String, Object> nestedConfig = new LinkedHashMap<>();
        nestedConfig.put("mode", "fast");
        sharedConfig.put("sdk", nestedConfig);

        TaskDispatchItem item = new TaskDispatchItem(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-1",
                "route-1",
                "worker-1",
                "ctx-1",
                "batch-1",
                input,
                sharedConfig
        );

        nestedInput.put("target", "worker-b");
        nestedConfig.put("mode", "slow");

        assertEquals("worker-a", ((Map<?, ?>) item.getInput().get("payload")).get("target"));
        assertEquals("fast", ((Map<?, ?>) item.getSharedConfig().get("sdk")).get("mode"));
    }

    @Test
    void rejectsBlankTaskIdentityFields() {
        IllegalArgumentException taskIdError = assertThrows(
                IllegalArgumentException.class,
                () -> new TaskDispatchItem(
                        " ",
                        "msg-1",
                        "crawler.fetch-page",
                        "task-name",
                        "demoApp",
                        "agent",
                        0,
                        "attempt-1",
                        "route-1",
                        "worker-1",
                        "ctx-1",
                        "batch-1",
                        Map.of(),
                        Map.of()
                )
        );
        assertEquals("taskId must not be blank", taskIdError.getMessage());

        IllegalArgumentException messageIdError = assertThrows(
                IllegalArgumentException.class,
                () -> new TaskDispatchItem(
                        "task-1",
                        " ",
                        "crawler.fetch-page",
                        "task-name",
                        "demoApp",
                        "agent",
                        0,
                        "attempt-1",
                        "route-1",
                        "worker-1",
                        "ctx-1",
                        "batch-1",
                        Map.of(),
                        Map.of()
                )
        );
        assertEquals("messageId must not be blank", messageIdError.getMessage());

        TaskDispatchItem genericItem = new TaskDispatchItem(
                "task-1",
                "msg-1",
                " ",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-1",
                "route-1",
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of(),
                Map.of()
        );
        assertNull(genericItem.getEventCode());
    }

    @Test
    void genericDispatchItemsHaveNoRouteKeyUntilTransportAddressIsResolved() {
        TaskDispatchItem item = new TaskDispatchItem(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of(),
                Map.of()
        );

        assertNull(item.routeKey());
    }

    @Test
    void transportDecodedItemsRetainCanonicalRouteKeyForReplyPath() {
        TaskDispatchItem item = TaskDispatchItem.fromDecodedTransportPayload(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "attempt-1",
                "route-9",
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of("target", "worker-a"),
                Map.of("mode", "fast")
        );

        assertEquals("route-9", item.routeKey());
    }

    private TaskDispatchBinding binding(Map<String, Object> payload) {
        return binding(payload, "ctx-1");
    }

    private TaskDispatchBinding binding(Map<String, Object> payload, String workerContextId) {
        return new TaskDispatchBinding(
                "task-1",
                "msg-1",
                "binding.event",
                payload,
                null,
                0,
                "attempt-1",
                1,
                null,
                "worker-1",
                workerContextId,
                "batch-1"
        );
    }
}
