package com.xa.mass.starter;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.starter.worker.WebSocketTaskMessageMapper;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GatewayTaskMsgPublisher implements TaskMsgDispatchListener, TaskDispatchChannel {

    private static final Logger logger = LoggerFactory.getLogger(GatewayTaskMsgPublisher.class);

    private final DispatchRuntimeContext dispatchRuntimeContext;
    private final WebSocketTaskMessageMapper messageMapper = new WebSocketTaskMessageMapper();

    public GatewayTaskMsgPublisher(DispatchRuntimeContext dispatchRuntimeContext) {
        this.dispatchRuntimeContext = dispatchRuntimeContext;
    }

    @Override
    public void onTaskMsgsReady(Task task, List<TaskMsg> taskMsgs) {
        dispatchTaskMessages(task, taskMsgs);
    }

    @Override
    public void dispatchTaskMessages(Task task, List<TaskMsg> taskMsgs) {
        if (dispatchRuntimeContext == null
                || dispatchRuntimeContext.getMessageTransporter() == null
                || dispatchRuntimeContext.getMessageCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or transporter is unavailable");
            return;
        }

        String eventCode = TaskSharedConfig.sdkEventCode(task);
        for (TaskMsg taskMsg : taskMsgs) {
            TaskDispatchItem dispatchItem = TaskDispatchItem.from(task, taskMsg);
            MassMessage message = messageMapper.toDispatchMessage(dispatchItem);
            String json = dispatchRuntimeContext.getMessageCodec().encode(message);
            Envelope envelope = Envelope.builder()
                    .workerId(taskMsg.getLatestAttemptWorkerId())
                    .eventCode(eventCode)
                    .project(task.getProject())
                    .traceId(taskMsg.getMsgId())
                    .receivedAt(System.currentTimeMillis())
                    .rawJson(json)
                    .build();
            dispatchRuntimeContext.getMessageTransporter().sendOutput(envelope);
        }
    }
}
