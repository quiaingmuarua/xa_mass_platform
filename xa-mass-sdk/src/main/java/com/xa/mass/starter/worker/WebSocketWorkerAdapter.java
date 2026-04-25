package com.xa.mass.starter.worker;

import com.xa.mass.engine.worker.WorkerAdapter;
import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * WebSocket-backed worker adapter.
 */
public class WebSocketWorkerAdapter implements WorkerAdapter {

    public static final String PROTOCOL = "websocket";

    private static final Logger logger = LoggerFactory.getLogger(WebSocketWorkerAdapter.class);

    private final TaskDispatchChannel taskDispatchChannel;

    public WebSocketWorkerAdapter(TaskDispatchChannel taskDispatchChannel) {
        this.taskDispatchChannel = taskDispatchChannel;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public Set<String> aliases() {
        return Set.of("ws", WorkerTransportHints.REALTIME, "push");
    }

    @Override
    public void dispatchTaskItems(List<TaskDispatchItem> items) {
        if (taskDispatchChannel == null) {
            logger.warn("Skip task message publishing because WebSocket dispatch channel is unavailable");
            return;
        }
        if (items == null || items.isEmpty()) {
            return;
        }
        taskDispatchChannel.dispatchTaskItems(items);
    }
}
