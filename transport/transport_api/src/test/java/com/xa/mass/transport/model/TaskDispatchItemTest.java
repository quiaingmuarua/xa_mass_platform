package com.xa.mass.transport.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskDispatchItemTest {

    @Test
    void unwrapsSdkJsonPayloadForTransportConsumers() {
        Task task = taskWithSdkPayloadType("JSON");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of(
                "type", "json",
                "data", Map.of("url", "https://example.test/page-1")
        ));

        TaskDispatchItem item = TaskDispatchItem.from(task, taskMsg);

        assertEquals("https://example.test/page-1", item.getInput().get("url"));
    }

    @Test
    void unwrapsSdkTextPayloadForTransportConsumers() {
        Task task = taskWithSdkPayloadType("TEXT");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of(
                "type", "text",
                "text", "hello"
        ));

        TaskDispatchItem item = TaskDispatchItem.from(task, taskMsg);

        assertEquals(Map.of("text", "hello"), item.getInput());
    }

    @Test
    void keepsPlainInputsUntouchedWhenNoSdkWrapperMetadataExists() {
        Task task = new Task();
        task.setTid("task-1");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of("target", "worker-a"));

        TaskDispatchItem item = TaskDispatchItem.from(task, taskMsg);

        assertEquals(Map.of("target", "worker-a"), item.getInput());
    }

    @Test
    void carriesLatestAttemptIdentityForInternalResultCorrelation() {
        Task task = new Task();
        task.setTid("task-1");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of("target", "worker-a"));
        taskMsg.applyLatestAttemptProjection("attempt-1", "worker-1", "ctx-1", "batch-1");

        TaskDispatchItem item = TaskDispatchItem.from(task, taskMsg);

        assertEquals("attempt-1", item.attemptId());
        assertEquals("worker-1", item.getWorkerId());
        assertEquals("ctx-1", item.getWorkerContextId());
        assertEquals("batch-1", item.getBatchId());
    }

    private Task taskWithSdkPayloadType(String payloadType) {
        Task task = new Task();
        task.setTid("task-1");
        task.setSharedConfig(Map.of(
                "_sdk", Map.of("payloadType", payloadType)
        ));
        return task;
    }
}
