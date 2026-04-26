package com.xa.mass.transport.websocket.worker;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * WebSocket-backed realtime worker adapter.
 *
 * <p>This adapter exposes concrete adapter identity {@code websocket} while
 * still belonging to the coarse {@code realtime} transport family.
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
        return Set.of("ws");
    }

    @Override
    public List<DispatchOutcome> dispatchTaskItems(List<TaskDispatchItem> items) {
        if (taskDispatchChannel == null) {
            logger.warn("Skip realtime task dispatch because WebSocket dispatch channel is unavailable");
            if (items == null || items.isEmpty()) {
                return List.of();
            }
            return items.stream()
                    .map(item -> invalidItem(item)
                            ? DispatchOutcome.invalid(PROTOCOL, item, "workerId must not be blank")
                            : DispatchOutcome.adapterUnavailable(PROTOCOL, item, "WebSocket dispatch channel is unavailable"))
                    .toList();
        }
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return taskDispatchChannel.dispatchTaskItems(items);
    }

    private boolean invalidItem(TaskDispatchItem item) {
        return item == null || item.getWorkerId() == null || item.getWorkerId().isBlank();
    }
}
