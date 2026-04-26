package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
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

    private final WebSocketDispatchRuntimeContext context;

    public WebSocketTaskDispatchChannel(WebSocketDispatchRuntimeContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (context.getEndpointRegistry() == null || context.getFrameCodec() == null) {
            logger.warn("Skip task message publishing because dispatcher context or endpoint registry is unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem dispatchItem : items) {
            String rawJson = context.getFrameCodec().encodeCanonicalTaskDispatch(dispatchItem);
            boolean sent = context.getEndpointRegistry().sendMessage(dispatchItem.getWorkerId(), rawJson);
            if (!sent) {
                logger.warn("WebSocket outbound skipped because endpoint is unavailable: workerId={}, traceId={}",
                        dispatchItem.getWorkerId(),
                        dispatchItem.getMessageId());
            }
        }
    }
}
