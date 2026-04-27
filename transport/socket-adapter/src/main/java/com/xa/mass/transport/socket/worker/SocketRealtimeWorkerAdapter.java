package com.xa.mass.transport.socket.worker;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.channel.TaskDispatchChannel;
import com.xa.mass.transport.runtime.DelegatingWorkerAdapter;

/**
 * Raw TCP socket-backed realtime worker adapter.
 */
public final class SocketRealtimeWorkerAdapter extends DelegatingWorkerAdapter {

    public static final String PROTOCOL = "socket";

    public SocketRealtimeWorkerAdapter(TaskDispatchChannel taskDispatchChannel) {
        super(
                PROTOCOL,
                WorkerTransportHints.REALTIME,
                taskDispatchChannel,
                "socket dispatch channel is unavailable"
        );
    }
}
