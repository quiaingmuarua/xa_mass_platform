package com.xa.mass.transport.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void exposesSeparatedRuntimeMetadataAndWireView() {
        Task task = taskWithSdkPayloadType("JSON");

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), binding(Map.of(
                "type", "json",
                "data", Map.of("url", "https://example.test/page-1")
        )));

        assertEquals("attempt-1", item.runtimeMetadata().attemptId());
        assertEquals("worker-1", item.runtimeMetadata().workerId());
        assertEquals("msg-1", item.wireView().messageId());
        assertEquals("task-1", item.wireView().taskId());
        assertEquals("worker-1", item.wireView().workerId());
        assertEquals("ctx-1", item.wireView().workerContextId());
        assertEquals("batch-1", item.wireView().batchId());
        assertEquals("https://example.test/page-1", item.wireView().input().get("url"));
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
