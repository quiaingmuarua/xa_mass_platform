package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;

@FunctionalInterface
public interface MessageOutboundMiddleware {

    boolean handle(OutboundDelivery delivery, DispatchRuntimeContext context);
}
