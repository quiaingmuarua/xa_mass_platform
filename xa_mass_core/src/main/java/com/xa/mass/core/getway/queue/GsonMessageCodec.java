package com.xa.mass.core.getway.queue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.xa.mass.core.getway.model.massMessage.MassMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Gson 的消息编解码器实现
 */
public class GsonMessageCodec implements MessageCodec {
    
    private static final Logger logger = LoggerFactory.getLogger(GsonMessageCodec.class);
    private final Gson gson;
    
    public GsonMessageCodec() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }
    
    public GsonMessageCodec(Gson gson) {
        this.gson = gson;
    }
    
    @Override
    public String encode(MassMessage message) {
        try {
            return gson.toJson(message);
        } catch (Exception e) {
            logger.error("Failed to encode message: {}", message, e);
            return null;
        }
    }
    
    @Override
    public MassMessage decode(String json) {
        try {
            return gson.fromJson(json, MassMessage.class);
        } catch (Exception e) {
            logger.error("Failed to decode JSON: {}", json, e);
            return null;
        }
    }
    
    @Override
    public boolean isValid(String json) {
        try {
            gson.fromJson(json, MassMessage.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取底层的 Gson 实例（用于向后兼容）
     * @return Gson 实例
     */
    public Gson getGson() {
        return gson;
    }
} 