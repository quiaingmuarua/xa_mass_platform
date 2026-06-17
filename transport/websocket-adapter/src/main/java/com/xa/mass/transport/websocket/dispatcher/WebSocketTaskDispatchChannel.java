package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * WebSocket adapter-owned task dispatch channel.
 */
public final class WebSocketTaskDispatchChannel implements AdapterCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketTaskDispatchChannel.class);

    private final WebSocketCommandDispatchContext context;
    private final TransportDeliveryService deliveryService;

    public WebSocketTaskDispatchChannel(WebSocketCommandDispatchContext context,
                                        TransportDeliveryService deliveryService) {
        this.context = Objects.requireNonNull(context, "context");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DeliveryCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        if (context.getEndpointRegistry() == null) {
            logger.warn("Skip task message publishing because dispatcher context or endpoint registry is unavailable");
            return deliveryService.sendDirect(
                    adapterId(),
                    commands,
                    null,
                    "dispatcher context or endpoint registry is unavailable"
            );
        }
        return deliveryService.sendDirect(
                adapterId(),
                commands,
                command -> {
                    boolean sent = context.getEndpointRegistry().sendToSelectedWorker(
                            adapterId(),
                            command.getSelectedWorkerId(),
                            command.getPayload()
                    );
                    if (!sent) {
                        logger.warn("WebSocket outbound skipped because endpoint is unavailable: selectedWorkerId={}, traceId={}",
                                command.getSelectedWorkerId(), null);
                    }
                    return sent;
                },
                "dispatcher context or endpoint registry is unavailable"
        );
    }

    private String adapterId() {
        return context.getAdapterId();
    }
}
