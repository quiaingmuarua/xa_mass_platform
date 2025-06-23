package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.gateway.queue.MessageTransporter;

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