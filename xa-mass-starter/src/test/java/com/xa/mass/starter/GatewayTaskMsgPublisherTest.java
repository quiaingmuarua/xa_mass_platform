package com.xa.mass.starter;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.enums.MessageDirection;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.model.payload.TaskPayload;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.GsonMessageCodec;
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
    void publishesTaskMessagesToOutputTransporter() {
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

        publisher.onTaskMsgsReady(task, List.of(taskMsg));

        ArgumentCaptor<Envelope> captor = ArgumentCaptor.forClass(Envelope.class);
        verify(transporter).sendOutput(captor.capture());

        Envelope envelope = captor.getValue();
        assertEquals("device-1", envelope.getDeviceId());
        assertEquals(GatewayTaskMsgPublisher.DEFAULT_CONN_ROLE, envelope.getConnRole());
        assertEquals("demoApp", envelope.getProject());
        assertEquals("msg-1", envelope.getTraceId());

        MassMessage message = codec.decode(envelope.getRawJson());
        assertNotNull(message);
        assertEquals("msg-1", message.getMsgId());
        assertEquals(MessageType.TASK, message.getMsgType());
        assertEquals("step", message.getSubMsgType());
        assertEquals(MessageDirection.SERVER, message.getFrom());
        assertEquals("device-1", message.getContext().getDeviceId());
        assertEquals(GatewayTaskMsgPublisher.DEFAULT_CONN_ROLE, message.getContext().getConnRole());
        assertEquals("task-1", message.getContext().getTid());

        TaskPayload payload = new com.google.gson.Gson().fromJson(message.getPayload(), TaskPayload.class);
        assertNotNull(payload);
        assertEquals(1, payload.getSteps().size());
        assertEquals("batch-0", payload.getSteps().get(0).getStepId());
        assertEquals("task-dispatch", payload.getSteps().get(0).getAction());
    }

    private Task task() {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskName("task-name");
        task.setProject("demoApp");
        task.setTextContent("hello");
        return task;
    }

    private TaskMsg taskMsg() {
        TaskMsg taskMsg = new TaskMsg("msg-1", "task-1", "target-1");
        taskMsg.setDeviceId("device-1");
        taskMsg.setTokenId("token-1");
        taskMsg.setBatchId("batch-0");
        return taskMsg;
    }
}
