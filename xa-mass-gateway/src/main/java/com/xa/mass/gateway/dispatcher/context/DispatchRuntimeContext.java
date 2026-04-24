package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.gateway.queue.WebSocketGatewayFrameCodec;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.channel.WorkerSystemEventChannel;

/**
 * Gateway dispatch runtime context.
 */
public interface DispatchRuntimeContext {

    WorkerEndpointRegistry getEndpointRegistry();

    WebSocketGatewayFrameCodec getFrameCodec();

    MessageTransporter<String, OutboundDelivery> getMessageTransporter();

    TaskResultIngestChannel getTaskResultIngestChannel();

    WorkerSystemEventChannel getSystemEventChannel();

    ControlEventRequestFrameBridge getControlEventRequestFrameBridge();

    ControlEventResponseFrameSink getControlEventResponseFrameSink();
}
