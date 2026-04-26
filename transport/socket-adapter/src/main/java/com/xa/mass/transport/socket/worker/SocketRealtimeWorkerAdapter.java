package com.xa.mass.transport.socket.worker;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.runtime.RuntimeDispatchOutcomes;
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
            return RuntimeDispatchOutcomes.adapterUnavailable(
                    PROTOCOL,
                    items,
                    "socket dispatch channel is unavailable"
            );
        }
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return taskDispatchChannel.dispatchTaskItems(items);
    }
}
