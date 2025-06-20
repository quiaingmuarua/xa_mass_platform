package com.xa.mass.core.getway.dispatcher;

import com.xa.mass.core.getway.dispatcher.context.DispatchRuntimeContext;

/**
 * 分发器上下文注册表
 * 支持注册和获取 DispatchRuntimeContext 及其子接口
 */
public class DispatcherContextRegistry {
    private static DispatchRuntimeContext dispatcherContext;

    /**
     * 注册分发运行时上下文
     * @param ctx 分发运行时上下文
     */
    public static void register(DispatchRuntimeContext ctx) {
        dispatcherContext = ctx;
    }

    /**
     * 获取完整的分发运行时上下文
     * @return 分发运行时上下文
     */
    public static DispatchRuntimeContext get() {
        return dispatcherContext;
    }

    /**
     * 获取会话上下文
     * @return 会话上下文，如果未注册则返回 null
     */
    public static com.xa.mass.core.getway.dispatcher.context.SessionContext getSessionContext() {
        return dispatcherContext;
    }

    /**
     * 获取编解码上下文
     * @return 编解码上下文，如果未注册则返回 null
     */
    public static com.xa.mass.core.getway.dispatcher.context.CodecContext getCodecContext() {
        return dispatcherContext;
    }

    /**
     * 获取传输上下文
     * @return 传输上下文，如果未注册则返回 null
     */
    public static com.xa.mass.core.getway.dispatcher.context.TransportContext getTransportContext() {
        return dispatcherContext;
    }

    /**
     * 获取处理器注册表上下文
     * @return 处理器注册表上下文，如果未注册则返回 null
     */
    public static com.xa.mass.core.getway.dispatcher.context.HandlerRegistryContext getHandlerRegistryContext() {
        return dispatcherContext;
    }

    /**
     * 获取中间件上下文
     * @return 中间件上下文，如果未注册则返回 null
     */
    public static com.xa.mass.core.getway.dispatcher.context.MiddlewareContext getMiddlewareContext() {
        return dispatcherContext;
    }
} 