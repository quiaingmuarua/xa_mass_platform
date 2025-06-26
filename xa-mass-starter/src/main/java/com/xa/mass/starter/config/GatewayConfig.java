package com.xa.mass.starter.config;

import com.xa.mass.gateway.queue.MessageTransporterFactory;
import com.xa.mass.gateway.queue.MessageQueue;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodecFactory;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.MessageTransporter;

/**
 * 网关配置类
 */
public  class GatewayConfig {
    private boolean enabled = true;
    private int maxConnections = 1000;

    // 消息传输器配置
    private MessageTransporterFactory.TransporterType transporterType = MessageTransporterFactory.TransporterType.QUEUE_BASED;
    private MessageQueue<Envelope> inputQueue;
    private MessageQueue<Envelope> outputQueue;

    // 外部API配置（当使用API_BASED传输器时）
    private String inputApiUrl;
    private String outputApiUrl;
    private String apiKey;

    // 编解码器配置
    private MessageCodecFactory.CodecType codecType = MessageCodecFactory.CodecType.GSON;
    private MessageCodec messageCodec;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }

    /**
     * 创建消息传输器
     */
    public MessageTransporter createMessageTransporter() {
        switch (transporterType) {
            case QUEUE_BASED:
                if (inputQueue == null || outputQueue == null) {
                    throw new IllegalStateException("QUEUE_BASED 类型需要提供 inputQueue 和 outputQueue");
                }
                return MessageTransporterFactory.createQueueBased(inputQueue, outputQueue);
            case MULTI_LEVEL:
                return MessageTransporterFactory.createMultiLevel();
            case API_BASED:
                if (inputApiUrl == null || outputApiUrl == null || apiKey == null) {
                    throw new IllegalStateException("API_BASED 类型需要提供 inputApiUrl, outputApiUrl 和 apiKey");
                }
                return MessageTransporterFactory.createApiBased(inputApiUrl, outputApiUrl, apiKey);
            default:
                throw new IllegalArgumentException("不支持的传输器类型: " + transporterType);
        }
    }

    /**
     * 创建消息编解码器
     */
    public MessageCodec createMessageCodec() {
        if (messageCodec != null) {
            return messageCodec;
        }
        return MessageCodecFactory.create(codecType);
    }

    // Getter 和 Setter 方法
    public MessageTransporterFactory.TransporterType getTransporterType() { return transporterType; }
    public void setTransporterType(MessageTransporterFactory.TransporterType transporterType) { this.transporterType = transporterType; }

    public MessageQueue<Envelope> getInputQueue() { return inputQueue; }
    public void setInputQueue(MessageQueue<Envelope> inputQueue) { this.inputQueue = inputQueue; }

    public MessageQueue<Envelope> getOutputQueue() { return outputQueue; }
    public void setOutputQueue(MessageQueue<Envelope> outputQueue) { this.outputQueue = outputQueue; }

    public String getInputApiUrl() { return inputApiUrl; }
    public void setInputApiUrl(String inputApiUrl) { this.inputApiUrl = inputApiUrl; }

    public String getOutputApiUrl() { return outputApiUrl; }
    public void setOutputApiUrl(String outputApiUrl) { this.outputApiUrl = outputApiUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public MessageCodecFactory.CodecType getCodecType() { return codecType; }
    public void setCodecType(MessageCodecFactory.CodecType codecType) { this.codecType = codecType; }

    public MessageCodec getMessageCodec() { return messageCodec; }
    public void setMessageCodec(MessageCodec messageCodec) { this.messageCodec = messageCodec; }
}