package com.xa.mass.starter.worker;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.transport.WorkerEndpointRoles;
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
    private final MessageCodec messageCodec;

    public WebSocketWorkerAdapter(MessageTransporter<String, OutboundDelivery> messageTransporter,
                                  MessageCodec messageCodec) {
        this.messageTransporter = messageTransporter;
        this.messageCodec = messageCodec;
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
        if (messageTransporter == null || messageCodec == null) {
            logger.warn("Skip task message publishing because WebSocket adapter dependencies are unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem dispatchItem : items) {
            String json = messageCodec.encodeTaskDispatch(dispatchItem);
            messageTransporter.sendOutput(new OutboundDelivery(
                    dispatchItem.getWorkerId(),
                    WorkerEndpointRoles.TASK_DISPATCH,
                    json,
                    dispatchItem.getMsgId()
            ));
        }
    }
}
