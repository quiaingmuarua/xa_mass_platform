package com.xa.mass.starter;

import com.google.gson.JsonObject;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.GsonMessageCodec;
import com.xa.mass.transport.WorkerEndpointRoles;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class GatewayTaskMsgPublisherTest {

    @Test
    void publishesDispatchItemsToOutputTransporter() {
        DispatchRuntimeContext context = mock(DispatchRuntimeContext.class);
        @SuppressWarnings("unchecked")
        com.xa.mass.base.channel.tranporter.MessageTransporter<Envelope> transporter =
                mock(com.xa.mass.base.channel.tranporter.MessageTransporter.class);
        GsonMessageCodec codec = new GsonMessageCodec();
        when(context.getMessageTransporter()).thenReturn(transporter);
        when(context.getMessageCodec()).thenReturn(codec);

        GatewayTaskMsgPublisher publisher = new GatewayTaskMsgPublisher(context);
        Task task = task();
        TaskMsg taskMsg = taskMsg();

        publisher.dispatchTaskItems(List.of(com.xa.mass.transport.model.TaskDispatchItem.from(task, taskMsg)));

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(transporter).sendOutput(captor.capture());

        Envelope envelope = captor.getValue();
        assertEquals("worker-1", envelope.getWorkerId());
        assertEquals(WorkerEndpointRoles.TASK_DISPATCH, envelope.getConnRole());
        assertEquals("crawler.fetch-page", envelope.getEventCode());
        assertEquals("demoApp", envelope.getProject());
        assertEquals("msg-1", envelope.getTraceId());

        MassMessage message = codec.decode(envelope.getRawJson());
        assertNotNull(message);
        assertEquals("msg-1", message.getMsgId());
        assertEquals(MessageType.TASK, message.getMsgType());
        assertEquals("step", message.getSubMsgType());
        assertEquals(MessageDirection.SERVER, message.getFrom());
        assertEquals("worker-1", message.getContext().getWorkerId());
        assertEquals(WorkerEndpointRoles.TASK_DISPATCH, message.getContext().getConnRole());
        assertEquals("task-1", message.getContext().getTaskId());

        JsonObject payload = new com.google.gson.Gson().fromJson(message.getPayload(), JsonObject.class);
        assertNotNull(payload);
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
