package com.xa.mass.gateway.dispatcher.context;

import com.xa.mass.gateway.queue.MessageCodec;

/**
 * 编解码上下文接口
 * 提供消息编解码功能
 * 支持不同的编解码实现（如 Gson、Jackson、protobuf 等）
 */
public interface CodecContext {
    /**
     * 获取消息编解码器
     * @return 消息编解码器
     */
    MessageCodec getMessageCodec();
} 