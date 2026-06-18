package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.websocket.session.WebSocketSessionRecord;
import com.xa.mass.transport.websocket.session.WebSocketSessionStore;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * WebSocket adapter-owned task dispatch channel.
 */
public final class WebSocketTaskDispatchChannel implements AdapterCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTaskDispatchChannel.class);

    private final WebSocketSessionStore sessionStore;

    public WebSocketTaskDispatchChannel(WebSocketSessionStore sessionStore) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DeliveryCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(commands.size());
        for (DeliveryCommand command : commands) {
            outcomes.add(dispatchOne(command));
        }
        return Collections.unmodifiableList(outcomes);
    }

    private DispatchOutcome dispatchOne(DeliveryCommand command) {
        if (command == null) {
            return DispatchOutcome.invalid((DeliveryCommand) null, "request must not be null");
        }
        WebSocketSessionRecord record = sessionStore.activeRecordForWorker(command.getSelectedWorkerId());
        if (record == null) {
            logger.warn("WebSocket outbound skipped because endpoint is unavailable: selectedWorkerId={}, traceId={}",
                    command.getSelectedWorkerId(), null);
            return DispatchOutcome.noEndpoint(command, "endpoint is unavailable");
        }
        try {
            record.channel().writeAndFlush(new TextWebSocketFrame(command.getPayload()));
            return DispatchOutcome.delivered(command);
        } catch (RuntimeException e) {
            logger.warn("WebSocket outbound failed: selectedWorkerId={}, reason={}",
                    command.getSelectedWorkerId(), e.getMessage());
            return DispatchOutcome.failed(command, e.getMessage(), true);
        }
    }
}
