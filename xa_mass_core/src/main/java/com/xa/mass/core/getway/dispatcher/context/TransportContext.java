package com.xa.mass.core.getway.dispatcher.context;

import com.xa.mass.core.getway.queue.MessageTransporter;

/**
 * 消息传输上下文接口
 * 提供消息传输功能
 */
public interface TransportContext {
    /**
     * 获取消息传输器
     * @return 消息传输器
     */
    MessageTransporter getMessageTransporter();
} 