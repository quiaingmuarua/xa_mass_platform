package com.xa.mass.gateway.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 消息编解码器工厂
 * 支持创建不同类型的消息编解码器
 */
public class MessageCodecFactory {
    
    public enum CodecType {
        GSON,
        // 未来可以添加更多类型
        // JACKSON,
        // PROTOBUF,
        // AVRO
    }
    
    /**
     * 创建默认的编解码器（Gson）
     * @return GsonMessageCodec 实例
     */
    public static MessageCodec createDefault() {
        return new GsonMessageCodec();
    }
    
    /**
     * 根据类型创建编解码器
     * @param type 编解码器类型
     * @return 对应的编解码器实例
     */
    public static MessageCodec create(CodecType type) {
        switch (type) {
            case GSON:
                return new GsonMessageCodec();
            default:
                throw new IllegalArgumentException("Unsupported codec type: " + type);
        }
    }
    
    /**
     * 创建自定义 Gson 编解码器
     * @param gson 自定义的 Gson 实例
     * @return GsonMessageCodec 实例
     */
    public static MessageCodec createGson(Gson gson) {
        return new GsonMessageCodec(gson);
    }
    
    /**
     * 创建自定义配置的 Gson 编解码器
     * @return 配置好的 GsonMessageCodec 实例
     */
    public static MessageCodec createGsonWithConfig() {
        Gson gson = new GsonBuilder()
                .setPrettyPrinting()
                .serializeNulls()
                .create();
        return new GsonMessageCodec(gson);
    }
} 