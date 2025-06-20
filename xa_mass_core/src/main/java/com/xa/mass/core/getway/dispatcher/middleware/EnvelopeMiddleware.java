package com.xa.mass.core.getway.dispatcher.middleware;

import com.xa.mass.core.getway.queue.Envelope;
import com.xa.mass.core.getway.dispatcher.context.DispatchRuntimeContext;

@FunctionalInterface
public interface EnvelopeMiddleware {
    /**
     * @param envelope      消息体
     * @param context       分发运行时上下文，包含队列、会话、配置等
     * @return 是否继续执行后续中间件
     */
    boolean handle(Envelope envelope, DispatchRuntimeContext context);
} 