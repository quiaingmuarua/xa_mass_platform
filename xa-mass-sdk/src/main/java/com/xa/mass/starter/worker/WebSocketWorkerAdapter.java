package com.xa.mass.starter.worker;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * WebSocket-backed worker adapter.
 */
public class WebSocketWorkerAdapter implements WorkerAdapter {

    public static final String PROTOCOL = "websocket";

    private static final Logger logger = LoggerFactory.getLogger(WebSocketWorkerAdapter.class);

    private final MessageTransporter<String, OutboundDelivery> messageTransporter;
    private final WebSocketGatewayFrameCodec frameCodec;

    public WebSocketWorkerAdapter(MessageTransporter<String, OutboundDelivery> messageTransporter,
                                  WebSocketGatewayFrameCodec frameCodec) {
        this.messageTransporter = messageTransporter;
        this.frameCodec = frameCodec;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ws", WorkerTransportHints.REALTIME, "push");
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (messageTransporter == null || frameCodec == null) {
            logger.warn("Skip task message publishing because WebSocket adapter dependencies are unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem dispatchItem : items) {
            String json = frameCodec.encodeCanonicalTaskDispatch(dispatchItem);
            messageTransporter.sendOutput(new OutboundDelivery(
                    dispatchItem.getWorkerId(),
                    json,
                    dispatchItem.getMsgId()
            ));
        }
    }
}
