package com.xa.mass.gateway.dispatcher;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket adapter-owned task dispatch bridge.
 */
public final class WebSocketTaskDispatchChannel implements TaskDispatchChannel {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTaskDispatchChannel.class);

    private final DispatchRuntimeContext context;

    public WebSocketTaskDispatchChannel(DispatchRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (context.getMessageTransporter() == null || context.getFrameCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or transporter is unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem dispatchItem : items) {
            String rawJson = context.getFrameCodec().encodeCanonicalTaskDispatch(dispatchItem);
            context.getMessageTransporter().sendOutput(new OutboundDelivery(
                    dispatchItem.getWorkerId(),
                    rawJson,
                    dispatchItem.getMessageId()
            ));
        }
    }
}
