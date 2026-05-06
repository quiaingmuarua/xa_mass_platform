package com.xa.mass.transport.model;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;
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

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), taskMsg, attempt());

        assertEquals("https://example.test/page-1", item.getInput().get("url"));
    }

    @Test
    void unwrapsSdkTextPayloadForTransportConsumers() {
        Task task = taskWithSdkPayloadType("TEXT");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of(
                "type", "text",
                "text", "hello"
        ));

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), taskMsg, attempt());

        assertEquals(Map.of("text", "hello"), item.getInput());
    }

    @Test
    void keepsPlainInputsUntouchedWhenNoSdkWrapperMetadataExists() {
        Task task = new Task();
        task.setTid("task-1");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of("target", "worker-a"));

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), taskMsg, attempt());

        assertEquals(Map.of("target", "worker-a"), item.getInput());
    }

    @Test
    void carriesLatestAttemptIdentityForInternalResultCorrelation() {
        Task task = new Task();
        task.setTid("task-1");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of("target", "worker-a"));
        TaskMsgAttempt attempt = attempt();

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), taskMsg, attempt);

        assertEquals("attempt-1", item.attemptId());
        assertEquals("worker-1", item.getWorkerId());
        assertEquals("ctx-1", item.getWorkerContextId());
        assertEquals("batch-1", item.getBatchId());
    }

    @Test
    void exposesSeparatedRuntimeMetadataAndWireView() {
        Task task = taskWithSdkPayloadType("JSON");
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", Map.of(
                "type", "json",
                "data", Map.of("url", "https://example.test/page-1")
        ));

        TaskDispatchItem item = TaskDispatchItem.from(TaskDispatchContext.from(task), taskMsg, attempt());

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

    private TaskMsgAttempt attempt() {
        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-1", "task-1", "msg-1", 1);
        attempt.setWorkerId("worker-1");
        attempt.setWorkerContextId("ctx-1");
        attempt.setBatchId("batch-1");
        return attempt;
    }
}
