package com.xa.mass.core.getway.middleware;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;

@FunctionalInterface
public interface ExceptionMiddleware {
    /**
     * @param envelope      消息体
     * @param context       DispatcherContext
     * @param exception     捕获的异常
     * @return 是否继续抛出异常
     */
    boolean handleException(Envelope envelope, DispatcherContext context, Exception exception);
} 