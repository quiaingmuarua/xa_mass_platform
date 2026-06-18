package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Adapter-owned dispatch channel for raw socket workers.
 */
public final class SocketTaskDispatchChannel implements AdapterCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SocketTaskDispatchChannel.class);

    private final SocketCommandDispatchContext context;
    private final TransportDeliveryService deliveryService;

    public SocketTaskDispatchChannel(SocketCommandDispatchContext context,
                                     TransportDeliveryService deliveryService) {
        this.context = Objects.requireNonNull(context, "context");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DeliveryCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        return deliveryService.sendDirect(
                adapterId(),
                commands,
                command -> {
                    String rawJson = context.getFrameCodec().encodeCanonicalTaskDispatch(command);
                    boolean sent = context.getEndpointRegistry().sendToSelectedWorker(
                            command.getSelectedWorkerId(),
                            rawJson
                    );
                    if (!sent) {
                        logger.warn("Socket outbound skipped because endpoint is unavailable: selectedWorkerId={}, traceId={}",
                                command.getSelectedWorkerId(), null);
                    }
                    return sent;
                },
                "socket dispatch channel is unavailable"
        );
    }

    private String adapterId() {
        return context.getAdapterId();
    }
}
