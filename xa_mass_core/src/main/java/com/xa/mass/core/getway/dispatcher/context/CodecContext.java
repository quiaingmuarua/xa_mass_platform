package com.xa.mass.core.getway.dispatcher.context;

import com.google.gson.Gson;

/**
 * 编解码上下文接口
 * 提供消息编解码功能
 * 如后续改成 Jackson 或 protobuf，替换更灵活
 */
public interface CodecContext {
    /**
     * 获取 Gson 编解码器
     * @return Gson 实例
     */
    Gson getGson();
} 