package com.xa.mass.starter;

import com.xa.mass.gateway.queue.InMemoryMessageQueue;
import com.xa.mass.gateway.queue.MessageTransporterFactory;

/**
 * MassApplication 使用示例
 * 展示如何使用新的应用主程序
 */
public class MassApplicationExample {
    
    public static void main(String[] args) {
        // 创建配置
        MassApplicationConfig config = new MassApplicationConfig();
        
        // 配置服务器
        config.setServerPort(18088);
        config.setWebSocketPath("/ws");
        
        // 配置消息传输器（使用队列）
        config.setTransporterType(MessageTransporterFactory.TransporterType.QUEUE_BASED);
        config.setInputQueue(new InMemoryMessageQueue());
        config.setOutputQueue(new InMemoryMessageQueue());
        
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
            System.out.println("Mass Application is running. Press Ctrl+C to stop.");
            
            // 等待中断信号
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down Mass Application...");
                app.stop();
            }));
            
            // 保持应用运行
            Thread.currentThread().join();
            
        } catch (InterruptedException e) {
            System.out.println("Application interrupted");
        } catch (Exception e) {
            System.err.println("Failed to start application: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 使用多级队列的示例
     */
    public static void multiLevelQueueExample() {
        MassApplicationConfig config = new MassApplicationConfig();
        config.setTransporterType(MessageTransporterFactory.TransporterType.MULTI_LEVEL);
        
        MassApplication app = new MassApplication(config);
        app.start();
    }
    
    /**
     * 使用外部API的示例
     */
    public static void externalApiExample() {
        MassApplicationConfig config = new MassApplicationConfig();
        config.setTransporterType(MessageTransporterFactory.TransporterType.API_BASED);
        config.setInputApiUrl("http://api.example.com/input");
        config.setOutputApiUrl("http://api.example.com/output");
        config.setApiKey("your-api-key");
        
        MassApplication app = new MassApplication(config);
        app.start();
    }
} 