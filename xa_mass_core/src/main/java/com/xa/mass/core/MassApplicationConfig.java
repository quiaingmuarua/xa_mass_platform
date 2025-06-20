package com.xa.mass.core;

import com.xa.mass.core.getway.queue.MessageTransporter;
import com.xa.mass.core.getway.queue.MessageTransporterFactory;
import com.xa.mass.core.getway.queue.MessageQueue;
import com.xa.mass.core.getway.queue.Envelope;

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
    
    // 网关配置
    private GatewayConfig gatewayConfig = new GatewayConfig();
    
    // 引擎配置
    private EngineConfig engineConfig = new EngineConfig();
    
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