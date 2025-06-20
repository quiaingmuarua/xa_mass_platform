package com.xa.mass.core.getway.dispatcher.context;

import com.xa.mass.core.getway.session.ServerSessionManager;

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