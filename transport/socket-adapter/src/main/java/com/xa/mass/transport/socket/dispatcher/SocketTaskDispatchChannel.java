package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Adapter-owned dispatch channel for raw socket workers.
 */
public final class SocketTaskDispatchChannel implements AdapterCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SocketTaskDispatchChannel.class);

    private final SocketTransportFrameCodec frameCodec;
    private final SocketSessionManager sessionManager;

    public SocketTaskDispatchChannel(SocketTransportFrameCodec frameCodec,
                                     SocketSessionManager sessionManager) {
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
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
            String rawJson = frameCodec.encodeCanonicalTaskDispatch(command);
            boolean sent = sessionManager.sendToWorker(command.getSelectedWorkerId(), rawJson);
            if (!sent) {
                logger.warn("Socket outbound skipped because endpoint is unavailable: selectedWorkerId={}, traceId={}",
                        command.getSelectedWorkerId(), null);
                return DispatchOutcome.noEndpoint(command, "endpoint is unavailable");
            }
            return DispatchOutcome.delivered(command);
        } catch (RuntimeException e) {
            logger.warn("Socket outbound failed: selectedWorkerId={}, reason={}",
                    command.getSelectedWorkerId(), e.getMessage());
            return DispatchOutcome.failed(command, e.getMessage(), true);
        }
    }
}
