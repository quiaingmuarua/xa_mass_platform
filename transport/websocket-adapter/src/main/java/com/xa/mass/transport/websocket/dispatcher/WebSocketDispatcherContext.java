package com.xa.mass.transport.websocket.dispatcher;

import com.xa.mass.transport.websocket.dispatcher.context.WebSocketDispatchRuntimeContext;
import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Concrete WebSocket adapter dispatch runtime context.
 */
public class WebSocketDispatcherContext implements WebSocketDispatchRuntimeContext {
    private final String adapterId;
    private final WorkerEndpointRegistry endpointRegistry;
    private final WebSocketTransportFrameCodec frameCodec;
    private final TaskResultIngestChannel taskResultIngestChannel;
    private final WorkerSystemEventChannel systemEventChannel;

    public WebSocketDispatcherContext(String adapterId,
                             WorkerEndpointRegistry endpointRegistry,
                             WebSocketTransportFrameCodec frameCodec,
                             TaskResultIngestChannel taskResultIngestChannel,
                             WorkerSystemEventChannel systemEventChannel) {
        this.adapterId = adapterId;
        this.endpointRegistry = endpointRegistry;
        this.frameCodec = frameCodec;
        this.taskResultIngestChannel = taskResultIngestChannel;
        this.systemEventChannel = systemEventChannel;
    }

    @Override
    public String getAdapterId() {
        return adapterId;
    }

    @Override
    public WorkerEndpointRegistry getEndpointRegistry() {
        return endpointRegistry;
    }

    @Override
    public WebSocketTransportFrameCodec getFrameCodec() {
        return frameCodec;
    }

    @Override
    public TaskResultIngestChannel getTaskResultIngestChannel() {
        return taskResultIngestChannel;
    }

    @Override
    public WorkerSystemEventChannel getSystemEventChannel() {
        return systemEventChannel;
    }
}
