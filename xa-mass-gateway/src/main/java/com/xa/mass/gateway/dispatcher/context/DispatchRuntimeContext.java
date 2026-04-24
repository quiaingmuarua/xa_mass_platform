package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.GatewayFrameRouter;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.OutboundDelivery;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.channel.TaskResultIngestChannel;

/**
 * Gateway dispatch runtime context.
 */
public interface DispatchRuntimeContext {

    WorkerEndpointRegistry getSessionManager();

    MessageCodec getMessageCodec();

    MessageTransporter<String, OutboundDelivery> getMessageTransporter();

    GatewayFrameRouter getFrameRouter();

    TaskResultIngestChannel getTaskResultIngestChannel();

    ControlEventRequestFrameBridge getControlEventRequestFrameBridge();

    ControlEventResponseFrameSink getControlEventResponseFrameSink();
}
