package com.xa.mass.starter.config;

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
        // gateway 配置
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(1000);
        config.getGatewayConfig().setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
        config.getGatewayConfig().setInputQueue(inputQueue);
        config.getGatewayConfig().setOutputQueue(outputQueue);
        // engine 配置
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
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(1000);
        config.getEngineConfig().setEnabled(true);
        config.getEngineConfig().setWorkerThreads(8);
        return config;
    }

    // Getter 和 Setter 方法

    public int getServerPort() { return serverPort; }
    public void setServerPort(int serverPort) { this.serverPort = serverPort; }

    public String getWebSocketPath() { return webSocketPath; }
    public void setWebSocketPath(String webSocketPath) { this.webSocketPath = webSocketPath; }

    public GatewayConfig getGatewayConfig() { return gatewayConfig; }
    public void setGatewayConfig(GatewayConfig gatewayConfig) { this.gatewayConfig = gatewayConfig; }

    public EngineConfig getEngineConfig() { return engineConfig; }
    public void setEngineConfig(EngineConfig engineConfig) { this.engineConfig = engineConfig; }

} 