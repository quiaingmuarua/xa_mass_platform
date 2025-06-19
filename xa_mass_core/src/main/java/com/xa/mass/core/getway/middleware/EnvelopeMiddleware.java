package com.xa.mass.core.getway.middleware;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.dispatcher.DispatcherContext;

@FunctionalInterface
public interface EnvelopeMiddleware {
    /**
     * @param envelope      消息体
     * @param context       DispatcherContext 包含队列、会话、配置等
     * @return 是否继续执行后续中间件
     */
    boolean handle(Envelope envelope, DispatcherContext context);
} 