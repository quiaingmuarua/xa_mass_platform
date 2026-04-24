package com.xa.mass.starter;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class GatewayTaskMsgPublisher implements TaskDispatchChannel {

    private static final Logger logger = LoggerFactory.getLogger(GatewayTaskMsgPublisher.class);

    private final DispatchRuntimeContext dispatchRuntimeContext;

    public GatewayTaskMsgPublisher(DispatchRuntimeContext dispatchRuntimeContext) {
        this.dispatchRuntimeContext = dispatchRuntimeContext;
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (dispatchRuntimeContext == null
                || dispatchRuntimeContext.getMessageTransporter() == null
                || dispatchRuntimeContext.getFrameCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or transporter is unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem dispatchItem : items) {
            String rawJson = dispatchRuntimeContext.getFrameCodec().encodeTaskDispatch(dispatchItem);
            dispatchRuntimeContext.getMessageTransporter().sendOutput(new OutboundDelivery(
                    dispatchItem.getWorkerId(),
                    rawJson,
                    dispatchItem.getMsgId()
            ));
        }
    }
}
