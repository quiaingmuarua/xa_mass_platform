package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.gateway.dispatcher.DispatcherContext;

/**
 * 中间件上下文接口
 * 提供中间件方向管理功能
 */
public interface MiddlewareContext {
    /**
     * 获取中间件方向
     * @return 中间件方向
     */
    DispatcherContext.MiddlewareDirection getDirection();
    
    /**
     * 设置中间件方向
     * @param direction 中间件方向
     */
    void setDirection(DispatcherContext.MiddlewareDirection direction);
} 