package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;

@FunctionalInterface
public interface MessageInboundMiddleware {

    boolean handle(String rawJson, DispatchRuntimeContext context);
}
