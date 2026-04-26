package com.xa.mass.transport.socket.worker;

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
 * Raw TCP socket-backed realtime worker adapter.
 */
public final class SocketRealtimeWorkerAdapter implements WorkerAdapter {

    public static final String PROTOCOL = "socket";

    private static final Logger logger = LoggerFactory.getLogger(SocketRealtimeWorkerAdapter.class);

    private final TaskDispatchChannel taskDispatchChannel;

    public SocketRealtimeWorkerAdapter(TaskDispatchChannel taskDispatchChannel) {
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
        return Set.of("tcp-socket");
    }

    @Override
    public List<DispatchOutcome> dispatchTaskItems(List<TaskDispatchItem> items) {
        if (taskDispatchChannel == null) {
            logger.warn("Skip socket task dispatch because socket dispatch channel is unavailable");
            if (items == null || items.isEmpty()) {
                return List.of();
            }
            return items.stream()
                    .map(item -> invalidItem(item)
                            ? DispatchOutcome.invalid(PROTOCOL, item, "workerId must not be blank")
                            : DispatchOutcome.adapterUnavailable(PROTOCOL, item, "socket dispatch channel is unavailable"))
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
