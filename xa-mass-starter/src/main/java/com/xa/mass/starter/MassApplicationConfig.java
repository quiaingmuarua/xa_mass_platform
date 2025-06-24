package com.xa.mass.starter;

import com.xa.mass.gateway.queue.MessageTransporter;
import com.xa.mass.gateway.queue.MessageTransporterFactory;
import com.xa.mass.gateway.queue.MessageQueue;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.MessageCodec;
import com.xa.mass.gateway.queue.MessageCodecFactory;

/**
 * Mass 应用配置类
 * 管理应用启动所需的各种配置参数
 */
public class MassApplicationConfig {
    // 服务器配置
    private int serverPort = 8080;
    private String webSocketPath = "/ws";

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

    // 网关配置
    private GatewayConfig gatewayConfig = new GatewayConfig();

    // 引擎配置
    private EngineConfig engineConfig = new EngineConfig();

    /**
     * 创建默认配置
     * 适用于大多数开发和生产环境
     * @return 默认配置实例
     */
    public static MassApplicationConfig createDefault() {
        return new MassApplicationConfig();
    }

    /**
     * 创建开发环境默认配置
     * @param port 服务器端口
     * @param inputQueue 输入队列
     * @param outputQueue 输出队列
     * @return 开发环境配置实例
     */
    public static MassApplicationConfig createDevelopment(int port, MessageQueue<Envelope> inputQueue, MessageQueue<Envelope> outputQueue) {
        MassApplicationConfig config = new MassApplicationConfig();
        config.setServerPort(port);
        config.setWebSocketPath("/ws");
        config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
        config.setInputQueue(inputQueue);
        config.setOutputQueue(outputQueue);
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(1000);
        config.getEngineConfig().setEnabled(true);
        config.getEngineConfig().setWorkerThreads(8);
        return config;
    }

    /**
     * 创建生产环境默认配置
     * @param port 服务器端口
     * @param inputQueue 输入队列
     * @param outputQueue 输出队列
     * @return 生产环境配置实例
     */
    public static MassApplicationConfig createProduction(int port, MessageQueue<Envelope> inputQueue, MessageQueue<Envelope> outputQueue) {
        MassApplicationConfig config = new MassApplicationConfig();
        config.setServerPort(port);
        config.setWebSocketPath("/ws");
        config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
        config.setInputQueue(inputQueue);
        config.setOutputQueue(outputQueue);
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(5000);
        config.getEngineConfig().setEnabled(true);
        config.getEngineConfig().setWorkerThreads(16);
        return config;
    }

    /**
     * 创建API模式配置
     * @param port 服务器端口
     * @param inputApiUrl 输入API URL
     * @param outputApiUrl 输出API URL
     * @param apiKey API密钥
     * @return API模式配置实例
     */
    public static MassApplicationConfig createApiMode(int port, String inputApiUrl, String outputApiUrl, String apiKey) {
        MassApplicationConfig config = new MassApplicationConfig();
        config.setServerPort(port);
        config.setWebSocketPath("/ws");
        config.setTransporterType(MessageTransporterFactory.TransporterType.API_BASED);
        config.setInputApiUrl(inputApiUrl);
        config.setOutputApiUrl(outputApiUrl);
        config.setApiKey(apiKey);
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(1000);
        config.getEngineConfig().setEnabled(true);
        config.getEngineConfig().setWorkerThreads(8);
        return config;
    }

    /**
     * 创建多级队列配置
     * @param port 服务器端口
     * @return 多级队列配置实例
     */
    public static MassApplicationConfig createMultiLevel(int port) {
        MassApplicationConfig config = new MassApplicationConfig();
        config.setServerPort(port);
        config.setWebSocketPath("/ws");
        config.setTransporterType(MessageTransporterFactory.TransporterType.MULTI_LEVEL);
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(1000);
        config.getEngineConfig().setEnabled(true);
        config.getEngineConfig().setWorkerThreads(8);
        return config;
    }

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

    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }

    public String getWebSocketPath() { return webSocketPath; }
    public void setWebSocketPath(String webSocketPath) { this.webSocketPath = webSocketPath; }

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

    public GatewayConfig getGatewayConfig() { return gatewayConfig; }
    public void setGatewayConfig(GatewayConfig gatewayConfig) { this.gatewayConfig = gatewayConfig; }

    public EngineConfig getEngineConfig() { return engineConfig; }
    public void setEngineConfig(EngineConfig engineConfig) { this.engineConfig = engineConfig; }

    /**
     * 网关配置类
     */
    public static class GatewayConfig {
        private boolean enabled = true;
        private int maxConnections = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
    }

    /**
     * 引擎配置类
     */
    public static class EngineConfig {
        private boolean enabled = true;
        private int workerThreads = 8;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
    }
} 