package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.gateway.session.ServerSessionManager;

/**
 * 会话上下文接口
 * 提供会话管理功能
 */
public interface SessionContext {
    /**
     * 获取会话管理器
     * @return 会话管理器
     */
    ServerSessionManager getSessionManager();
} 