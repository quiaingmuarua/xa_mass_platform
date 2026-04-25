package com.xa.mass.transport.websocket.worker;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * WebSocket-backed realtime worker adapter.
 *
 * <p>The current realtime delivery path is WebSocket-backed.
 * This adapter keeps that implementation label adapter-local while runtime
 * transport selection stays on the canonical {@code realtime} identity.
 */
public final class WebSocketRealtimeWorkerAdapter implements WorkerAdapter {

    public static final String PROTOCOL = "websocket";

    private static final Logger logger = LoggerFactory.getLogger(WebSocketRealtimeWorkerAdapter.class);

    private final TaskDispatchChannel taskDispatchChannel;

    public WebSocketRealtimeWorkerAdapter(TaskDispatchChannel taskDispatchChannel) {
        this.taskDispatchChannel = taskDispatchChannel;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public String transportHint() {
        return WorkerTransportHints.REALTIME;
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ws", WorkerTransportHints.REALTIME, "push");
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (taskDispatchChannel == null) {
            logger.warn("Skip realtime task dispatch because WebSocket dispatch channel is unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        taskDispatchChannel.dispatchTaskItems(items);
    }
}
