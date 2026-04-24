package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.GatewayFrameRouter;
import com.xa.mass.gateway.dispatcher.middleware.MiddlewareRegistry;
import com.xa.mass.gateway.dispatcher.port.ControlEventRequestFrameBridge;
import com.xa.mass.gateway.dispatcher.port.ControlEventResponseFrameSink;
import com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.MessageParser;
import com.xa.mass.transport.WorkerEndpointRegistry;

/**
 * Gateway dispatch runtime context.
 *
 * <p>This is an internal adapter-facing context, not a platform-wide extension
 * model. Keep it as one explicit runtime view instead of slicing it into many
 * tiny marker interfaces.
 */
public interface DispatchRuntimeContext {

    WorkerEndpointRegistry getSessionManager();

    MessageCodec getMessageCodec();

    MessageParser getMessageParser();

    MessageTransporter<Envelope> getMessageTransporter();

    MiddlewareRegistry getMiddlewareRegistry();

    GatewayFrameRouter getFrameRouter();

    void setFrameRouter(GatewayFrameRouter frameRouter);

    TaskStepFrameBridge getTaskStepFrameBridge();

    void setTaskStepFrameBridge(TaskStepFrameBridge taskStepFrameBridge);

    ControlEventRequestFrameBridge getControlEventRequestFrameBridge();

    void setControlEventRequestFrameBridge(ControlEventRequestFrameBridge controlEventRequestFrameBridge);

    ControlEventResponseFrameSink getControlEventResponseFrameSink();

    void setControlEventResponseFrameSink(ControlEventResponseFrameSink controlEventResponseFrameSink);
}
