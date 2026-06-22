package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.DispatchOutcomeFactory;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.websocket.session.WebSocketSessionController;
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

    private final WebSocketSessionController sessionController;

    public WebSocketTaskDispatchChannel(WebSocketSessionController sessionController) {
        this.sessionController = Objects.requireNonNull(sessionController, "sessionController");
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DispatchMessage> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(items.size());
        for (DispatchMessage item : items) {
            outcomes.add(dispatchOne(item));
        }
        return Collections.unmodifiableList(outcomes);
    }

    private DispatchOutcome dispatchOne(DispatchMessage item) {
        if (item == null) {
            return DispatchOutcome.invalid(null, null, null, "request must not be null");
        }
        try {
            boolean sent = sessionController.sendTextToWorker(item.selectedWorkerId(), item.payload());
            if (sent) {
                return DispatchOutcomeFactory.delivered(item);
            }
            logger.warn("WebSocket outbound skipped because endpoint is unavailable: selectedWorkerId={}, traceId={}",
                    item.selectedWorkerId(), null);
            return DispatchOutcomeFactory.noEndpoint(item, "endpoint is unavailable");
        } catch (RuntimeException e) {
            logger.warn("WebSocket outbound failed: selectedWorkerId={}, reason={}",
                    item.selectedWorkerId(), e.getMessage());
            return DispatchOutcomeFactory.failed(item, e.getMessage(), true);
        }
    }
}
