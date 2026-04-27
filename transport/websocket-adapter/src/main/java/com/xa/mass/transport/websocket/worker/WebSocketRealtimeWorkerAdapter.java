package com.xa.mass.transport.websocket.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.runtime.DelegatingWorkerAdapter;

/**
 * WebSocket-backed realtime worker adapter.
 *
 * <p>This adapter exposes concrete adapter identity {@code websocket} while
 * still belonging to the coarse {@code realtime} transport family.
 */
public final class WebSocketRealtimeWorkerAdapter extends DelegatingWorkerAdapter {

    public static final String PROTOCOL = "websocket";

    public WebSocketRealtimeWorkerAdapter(TaskDispatchChannel taskDispatchChannel) {
        super(
                PROTOCOL,
                WorkerTransportHints.REALTIME,
                taskDispatchChannel,
                "WebSocket dispatch channel is unavailable"
        );
    }
}
