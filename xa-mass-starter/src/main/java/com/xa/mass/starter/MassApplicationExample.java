package com.xa.mass.starter;

import com.xa.mass.gateway.queue.InMemoryMessageQueue;
import com.xa.mass.gateway.queue.MessageTransporterFactory;
import com.xa.mass.starter.config.MassApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MassApplication 使用示例
 * 展示如何使用新的应用主程序
 */
public class MassApplicationExample {
    
    private static final Logger log = LoggerFactory.getLogger(MassApplicationExample.class);
    
    public static void main(String[] args) {
        // 创建配置
        MassApplicationConfig config = new MassApplicationConfig();
        
        // 配置服务器
        config.setServerPort(18088);
        config.setWebSocketPath("/ws");
        
        // 配置消息传输器（使用队列）
        config.getGatewayConfig().setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
        config.getGatewayConfig().setInputQueue(new InMemoryMessageQueue());
        config.getGatewayConfig().setOutputQueue(new InMemoryMessageQueue());
        
        // 配置网关
        config.getGatewayConfig().setEnabled(true);
        config.getGatewayConfig().setMaxConnections(1000);
        
        // 配置引擎
        config.getEngineConfig().setEnabled(true);
        config.getEngineConfig().setWorkerThreads(8);
        
        // 创建并启动应用
        MassApplication app = new MassApplication(config);
        
        try {
            app.start();
            
            // 应用运行中...
            log.info("Mass Application is running. Press Ctrl+C to stop.");
            
            // 等待中断信号
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Shutting down Mass Application...");
                app.stop();
            }));
            
            // 保持应用运行
            Thread.currentThread().join();
            
        } catch (InterruptedException e) {
            log.info("Application interrupted");
        } catch (Exception e) {
            log.error("Failed to start application: " + e.getMessage(), e);
        }
    }
    
    /**
     * 使用多级队列的示例
     */
    public static void multiLevelQueueExample() {
        MassApplicationConfig config = new MassApplicationConfig();
        config.getGatewayConfig().setTransporterType(MessageTransporterFactory.TransporterType.MULTI_LEVEL);
        MassApplication app = new MassApplication(config);
        app.start();
    }
    
    /**
     * 使用外部API的示例
     */
    public static void externalApiExample() {
        MassApplicationConfig config = new MassApplicationConfig();
        config.getGatewayConfig().setTransporterType(MessageTransporterFactory.TransporterType.API_BASED);
        config.getGatewayConfig().setInputApiUrl("http://api.example.com/input");
        config.getGatewayConfig().setOutputApiUrl("http://api.example.com/output");
        config.getGatewayConfig().setApiKey("your-api-key");
        MassApplication app = new MassApplication(config);
        app.start();
    }
} 