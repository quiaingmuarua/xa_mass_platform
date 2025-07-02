package com.xa.mass.gateway.dispatcher.middleware;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.Envelope;

@FunctionalInterface
public interface ExceptionMiddleware {
    /**
     * @param envelope      消息体
     * @param context       分发运行时上下文
     * @param exception     捕获的异常
     * @return 是否继续抛出异常
     */
    boolean handleException(Envelope envelope, DispatchRuntimeContext context, Exception exception);
} 