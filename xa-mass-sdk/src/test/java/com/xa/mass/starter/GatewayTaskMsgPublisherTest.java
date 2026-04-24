package com.xa.mass.starter;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayTaskMsgPublisherTest {

    @Test
    void publishesDispatchItemsToOutputTransporter() {
        DispatchRuntimeContext context = mock(DispatchRuntimeContext.class);
        @SuppressWarnings("unchecked")
        com.xa.mass.base.channel.tranporter.MessageTransporter<String, OutboundDelivery> transporter =
                mock(com.xa.mass.base.channel.tranporter.MessageTransporter.class);
        WebSocketGatewayFrameCodec codec = new WebSocketGatewayFrameCodec();
        when(context.getMessageTransporter()).thenReturn(transporter);
        when(context.getFrameCodec()).thenReturn(codec);

        GatewayTaskMsgPublisher publisher = new GatewayTaskMsgPublisher(context);
        Task task = task();
        TaskMsg taskMsg = taskMsg();

        publisher.dispatchTaskItems(List.of(com.xa.mass.transport.model.TaskDispatchItem.from(task, taskMsg)));

        ArgumentCaptor<OutboundDelivery> captor = ArgumentCaptor.forClass(OutboundDelivery.class);
        verify(transporter).sendOutput(captor.capture());

        OutboundDelivery delivery = captor.getValue();
        assertEquals("worker-1", delivery.getWorkerId());
        assertEquals("msg-1", delivery.getTraceId());

        JsonObject message = codec.parseObject(delivery.getRawJson());
        assertNotNull(message);
        assertEquals("msg-1", message.get("msgId").getAsString());
        assertEquals("TASK", message.get("msgType").getAsString());
        assertEquals("step", message.get("subMsgType").getAsString());
        assertEquals("SERVER", message.get("from").getAsString());
        assertEquals("worker-1", message.getAsJsonObject("context").get("workerId").getAsString());
        assertEquals("task-1", message.getAsJsonObject("context").get("taskId").getAsString());

        JsonObject payload = message.getAsJsonObject("payload");
        assertNotNull(payload);
        assertEquals("crawler.fetch-page", payload.get("eventCode").getAsString());
        assertEquals(1, payload.getAsJsonArray("steps").size());
        JsonObject firstStep = payload.getAsJsonArray("steps").get(0).getAsJsonObject();
        assertEquals("batch-0", firstStep.get("stepId").getAsString());
        assertEquals("task-dispatch", firstStep.get("action").getAsString());
        assertEquals("demoApp", firstStep.getAsJsonObject("params").get("project").getAsString());
        assertEquals("agent-1", firstStep.getAsJsonObject("params").get("userId").getAsString());
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
