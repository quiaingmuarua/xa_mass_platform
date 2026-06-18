package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
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
        try {
            boolean sent = sessionController.sendTextToWorker(command.getSelectedWorkerId(), command.getPayload());
            if (sent) {
                return DispatchOutcome.delivered(command);
            }
            logger.warn("WebSocket outbound skipped because endpoint is unavailable: selectedWorkerId={}, traceId={}",
                    command.getSelectedWorkerId(), null);
            return DispatchOutcome.noEndpoint(command, "endpoint is unavailable");
        } catch (RuntimeException e) {
            logger.warn("WebSocket outbound failed: selectedWorkerId={}, reason={}",
                    command.getSelectedWorkerId(), e.getMessage());
            return DispatchOutcome.failed(command, e.getMessage(), true);
        }
    }
}
