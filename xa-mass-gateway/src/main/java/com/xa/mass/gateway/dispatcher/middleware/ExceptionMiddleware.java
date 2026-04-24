package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.OutboundDelivery;

@FunctionalInterface
public interface ExceptionMiddleware {

    boolean handleException(String rawJson,
                            OutboundDelivery delivery,
                            DispatchRuntimeContext context,
                            Exception exception);
}
