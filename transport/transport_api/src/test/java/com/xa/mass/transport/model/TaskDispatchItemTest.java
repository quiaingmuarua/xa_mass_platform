package com.xa.mass.transport.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TaskDispatchItemTest {

    @Test
    void unwrapsSdkJsonPayloadForTransportConsumers() {
        Task task = taskWithSdkPayloadType("JSON");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "type", "json",
                "data", Map.of("url", "https://example.test/page-1")
        )));

        assertEquals("https://example.test/page-1", item.getInput().get("url"));
    }

    @Test
    void unwrapsSdkTextPayloadForTransportConsumers() {
        Task task = taskWithSdkPayloadType("TEXT");

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
    void exposesDispatchFieldsDirectlyWithoutExtraWrapperObjects() {
        Task task = taskWithSdkPayloadType("JSON");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "type", "json",
                "data", Map.of("url", "https://example.test/page-1")
        )));

        assertEquals("attempt-1", item.attemptId());
        assertEquals("worker-1", item.getWorkerId());
        assertEquals("msg-1", item.getMessageId());
        assertEquals("task-1", item.getTaskId());
        assertEquals("ctx-1", item.getWorkerContextId());
        assertEquals("batch-1", item.getBatchId());
        assertEquals("https://example.test/page-1", item.getInput().get("url"));
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
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of("target", "worker-a"),
                Map.of("mode", "fast")
        );

        assertSame(item.toTransportPayload(), item.toTransportPayload());
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

    private Task taskWithSdkPayloadType(String payloadType) {
        Task task = new Task();
        task.setTid("task-1");
        task.setSharedConfig(Map.of(
                "_sdk", Map.of("payloadType", payloadType)
        ));
        return task;
    }

    private TaskDispatchBinding binding(Map<String, Object> payload) {
        return new TaskDispatchBinding(
                "task-1",
                "msg-1",
                null,
                payload,
                null,
                0,
                "attempt-1",
                1,
                null,
                "worker-1",
                "ctx-1",
                "batch-1"
        );
    }
}
