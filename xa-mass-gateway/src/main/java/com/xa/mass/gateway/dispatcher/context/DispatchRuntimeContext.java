package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.base.channel.tranporter.MessageTransporter;
import com.xa.mass.gateway.dispatcher.DispatcherContext;
import com.xa.mass.gateway.dispatcher.MessageHandlerRegistry;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodec;
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

    MessageTransporter<Envelope> getMessageTransporter();

    MessageHandlerRegistry getMessageHandlerRegistry();

    void setMessageHandlerRegistry(MessageHandlerRegistry messageHandlerRegistry);

    DispatcherContext.MiddlewareDirection getDirection();

    void setDirection(DispatcherContext.MiddlewareDirection direction);
}
