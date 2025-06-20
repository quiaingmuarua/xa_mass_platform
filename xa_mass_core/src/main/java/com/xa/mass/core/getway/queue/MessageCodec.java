package com.xa.mass.core.getway.queue;

import com.xa.mass.core.model.message.MassMessage;

/**
 * 消息编解码器接口
 * 提供消息的编码和解码功能
 * 支持不同的编解码实现（如 Gson、Jackson、protobuf 等）
 */
public interface MessageCodec {
    
    /**
     * 将消息编码为字符串
     * @param message 要编码的消息
     * @return 编码后的字符串
     */
    String encode(MassMessage message);
    
    /**
     * 将字符串解码为消息
     * @param json 要解码的字符串
     * @return 解码后的消息，如果解码失败返回 null
     */
    MassMessage decode(String json);
    
    /**
     * 检查字符串是否为有效的消息格式
     * @param json 要检查的字符串
     * @return 如果格式有效返回 true，否则返回 false
     */
    boolean isValid(String json);
} 