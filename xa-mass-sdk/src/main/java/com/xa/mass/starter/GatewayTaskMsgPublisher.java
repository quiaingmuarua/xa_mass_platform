package com.xa.mass.starter;

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

public class GatewayTaskMsgPublisher implements TaskDispatchChannel {

    private static final Logger logger = LoggerFactory.getLogger(GatewayTaskMsgPublisher.class);

    private final DispatchRuntimeContext dispatchRuntimeContext;
    private final WebSocketTaskMessageMapper messageMapper = new WebSocketTaskMessageMapper();

    public GatewayTaskMsgPublisher(DispatchRuntimeContext dispatchRuntimeContext) {
        this.dispatchRuntimeContext = dispatchRuntimeContext;
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (dispatchRuntimeContext == null
                || dispatchRuntimeContext.getMessageTransporter() == null
                || dispatchRuntimeContext.getMessageCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or transporter is unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem dispatchItem : items) {
            MassMessage message = messageMapper.toDispatchMessage(dispatchItem);
            String json = dispatchRuntimeContext.getMessageCodec().encode(message);
            Envelope envelope = Envelope.builder()
                    .workerId(dispatchItem.getWorkerId())
                    .eventCode(dispatchItem.getEventCode())
                    .project(dispatchItem.getProject())
                    .traceId(dispatchItem.getMsgId())
                    .receivedAt(System.currentTimeMillis())
                    .rawJson(json)
                    .build();
            dispatchRuntimeContext.getMessageTransporter().sendOutput(envelope);
        }
    }
}
