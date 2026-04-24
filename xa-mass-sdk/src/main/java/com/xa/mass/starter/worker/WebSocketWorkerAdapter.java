package com.xa.mass.starter.worker;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.transport.WorkerTransportHints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import com.xa.mass.transport.model.TaskDispatchItem;

/**
 * WebSocket-backed worker adapter.
 *
 * <p>This adapter now owns only the dispatch side: pushing task messages to
 * connected workers over WebSocket.
 *
 * <p>Inbound {@code TASK/step} result callbacks are handled separately by
 * {@code GatewayTaskResultHandler}, keeping {@link MassMessage} compatibility
 * concerns at the gateway boundary instead of on the transport-neutral worker
 * adapter contract.
 */
public class WebSocketWorkerAdapter implements WorkerAdapter {

    public static final String PROTOCOL = "websocket";

    private static final Logger logger = LoggerFactory.getLogger(WebSocketWorkerAdapter.class);

    private final DispatchRuntimeContext dispatchRuntimeContext;
    private final WebSocketTaskMessageMapper messageMapper = new WebSocketTaskMessageMapper();

    public WebSocketWorkerAdapter(DispatchRuntimeContext dispatchRuntimeContext) {
        this.dispatchRuntimeContext = dispatchRuntimeContext;
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
