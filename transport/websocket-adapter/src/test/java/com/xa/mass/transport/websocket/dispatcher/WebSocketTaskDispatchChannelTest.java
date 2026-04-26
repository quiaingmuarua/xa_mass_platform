package com.xa.mass.transport.websocket.dispatcher;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketTaskDispatchChannelTest {

    @Test
    void publishesDispatchItemsDirectlyToEndpointRegistry() {
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class);
        when(endpointRegistry.sendMessage(org.mockito.ArgumentMatchers.eq("worker-1"), any())).thenReturn(true);
        WebSocketTransportFrameCodec codec = new WebSocketTransportFrameCodec();
        WebSocketDispatcherContext context = new WebSocketDispatcherContext(
                endpointRegistry,
                codec,
                null,
                NoopWorkerSystemEventChannel.INSTANCE
        );

        WebSocketTaskDispatchChannel publisher = new WebSocketTaskDispatchChannel(context);
        Task task = task();
        TaskMsg taskMsg = taskMsg();

        publisher.dispatchTaskItems(List.of(com.xa.mass.transport.model.TaskDispatchItem.from(task, taskMsg)));

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(endpointRegistry).sendMessage(org.mockito.ArgumentMatchers.eq("worker-1"), captor.capture());

        JsonObject message = codec.parseObject(captor.getValue());
        assertNotNull(message);
        assertEquals("msg-1", message.get("messageId").getAsString());
        assertEquals("worker-1", message.get("workerId").getAsString());
        assertEquals("task-1", message.get("taskId").getAsString());
        assertEquals("crawler.fetch-page", message.get("eventCode").getAsString());
        assertEquals("worker-context-1", message.get("workerContextId").getAsString());
        assertEquals("batch-0", message.get("batchId").getAsString());
        assertEquals(0, message.get("retryCount").getAsInt());

        JsonObject input = message.getAsJsonObject("input");
        JsonObject sharedConfig = message.getAsJsonObject("sharedConfig");
        assertNotNull(input);
        assertNotNull(sharedConfig);
        assertEquals("target-1", input.get("target").getAsString());
        assertEquals("demoApp", message.get("project").getAsString());
        assertEquals("agent-1", message.get("userId").getAsString());
        assertEquals("hello", sharedConfig.get("textContent").getAsString());
    }

    private Task task() {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskName("task-name");
        task.setProject("demoApp");
        task.setUser(com.xa.mass.base.model.UserRef.of("agent-1"));
        task.setSharedConfig(java.util.Map.of(
                "textContent", "hello",
                "_sdk", java.util.Map.of("eventCode", "crawler.fetch-page")
        ));
        return task;
    }

    private TaskMsg taskMsg() {
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-1"));
        taskMsg.applyLatestAttemptProjection("worker-1", "worker-context-1", "batch-0");
        return taskMsg;
    }
}
