package com.xa.mass.transport.websocket.dispatcher.context;

import com.xa.mass.transport.websocket.queue.WebSocketTransportFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * WebSocket adapter dispatch runtime context.
 */
public interface WebSocketDispatchRuntimeContext {

    WorkerEndpointRegistry getEndpointRegistry();

    WebSocketTransportFrameCodec getFrameCodec();

    TaskResultIngestChannel getTaskResultIngestChannel();

    WorkerSystemEventChannel getSystemEventChannel();
}
