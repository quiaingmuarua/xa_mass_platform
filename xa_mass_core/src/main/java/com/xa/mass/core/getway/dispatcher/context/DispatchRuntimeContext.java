package com.xa.mass.core.getway.dispatcher.context;

/**
 * 分发运行时上下文接口
 * 组合所有上下文接口，提供完整的分发运行时环境
 */
public interface DispatchRuntimeContext extends
        SessionContext,
        CodecContext,
        TransportContext,
        HandlerRegistryContext,
        MiddlewareContext {
} 