package com.xa.mass.transport.socket.dispatcher;

import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.socket.protocol.SocketTransportFrameCodec;
import com.xa.mass.transport.socket.session.SocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Adapter-owned dispatch bridge for raw socket workers.
 */
public final class SocketTaskDispatchChannel implements TaskDispatchChannel {

    private static final Logger logger = LoggerFactory.getLogger(SocketTaskDispatchChannel.class);

    private final SocketSessionManager sessionManager;
    private final SocketTransportFrameCodec frameCodec;

    public SocketTaskDispatchChannel(SocketSessionManager sessionManager, SocketTransportFrameCodec frameCodec) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (TaskDispatchItem item : items) {
            String rawJson = frameCodec.encodeCanonicalTaskDispatch(item);
            if (!sessionManager.sendMessage(item.getWorkerId(), rawJson)) {
                logger.warn("Socket outbound skipped because endpoint is unavailable: workerId={}, messageId={}",
                        item.getWorkerId(), item.getMessageId());
            }
        }
    }
}
