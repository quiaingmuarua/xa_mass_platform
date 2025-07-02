package com.xa.mass.starter;

import com.xa.mass.gateway.queue.MessageQueue;
import com.xa.mass.gateway.queue.Envelope;
import com.xa.mass.gateway.queue.InMemoryMessageQueue;
import com.xa.mass.starter.builder.MassApplicationBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mass 应用使用示例
 * 展示简化后的架构：MassApplicationBuilder负责配置聚合，MassApplication负责生命周期管理
 */
public class MassApplicationExample {
    
    private static final Logger logger = LoggerFactory.getLogger(MassApplicationExample.class);
    
    public static void main(String[] args) {
        logger.info("🚀 Mass Application 简化架构示例");
        
        // 示例1: 快速创建开发环境应用
        exampleDevelopmentMode();
        
        // 示例2: 快速创建生产环境应用
        exampleProductionMode();
        
        // 示例3: 快速创建API模式应用
        exampleApiMode();
        
        // 示例4: 快速创建测试环境应用
        exampleTestMode();
        
        // 示例5: 自定义配置应用
        exampleCustomConfiguration();
        
        // 示例6: Mock模式应用
        exampleMockMode();
    }
    
    /**
     * 示例1: 开发环境模式
     * 架构：MassApplicationBuilder(配置) → MassApplication(生命周期)
     */
    private static void exampleDevelopmentMode() {
        logger.info("📝 示例1: 开发环境模式");
        
        // 创建内存队列
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue();
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue();
        
        // MassApplicationBuilder 负责配置聚合
        MassApplication app = MassApplicationBuilder.createDevelopment(8080, inputQueue, outputQueue);
        
        // MassApplication 负责生命周期管理
        app.start();
        
        logger.info("✅ 开发环境应用启动成功");
        
        // 停止应用
        app.stop();
    }
    
    /**
     * 示例2: 生产环境模式
     */
    private static void exampleProductionMode() {
        logger.info("📝 示例2: 生产环境模式");
        
        // 创建内存队列
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue();
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue();
        
        // 快速创建生产环境应用
        MassApplication app = MassApplicationBuilder.createProduction(8080, inputQueue, outputQueue);
        
        // 启动应用
        app.start();
        
        logger.info("✅ 生产环境应用启动成功");
        
        // 停止应用
        app.stop();
    }
    
    /**
     * 示例3: API模式
     */
    private static void exampleApiMode() {
        logger.info("📝 示例3: API模式");
        
        // 快速创建API模式应用
        MassApplication app = MassApplicationBuilder.createApiMode(
            8080, 
            "http://api.example.com/input", 
            "http://api.example.com/output", 
            "your-api-key"
        );
        
        // 启动应用
        app.start();
        
        logger.info("✅ API模式应用启动成功");
        
        // 停止应用
        app.stop();
    }
    
    /**
     * 示例4: 测试环境模式
     */
    private static void exampleTestMode() {
        logger.info("📝 示例4: 测试环境模式");
        
        // 快速创建测试环境应用
        MassApplication app = MassApplicationBuilder.createTest(8080);
        
        // 启动应用
        app.start();
        
        logger.info("✅ 测试环境应用启动成功");
        
        // 停止应用
        app.stop();
    }
    
    /**
     * 示例5: 自定义配置
     * 展示流式API的灵活性
     */
    private static void exampleCustomConfiguration() {
        logger.info("📝 示例5: 自定义配置");
        
        // 创建内存队列
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue();
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue();
        
        // 使用流式API自定义配置
        MassApplication app = MassApplicationBuilder.create()
                .server(9090, "/custom-ws")
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(2000)
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue)
                        .queueMode())
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(12)
                        .mockData("custom_mock_config.json"))
                .build();
        
        // 启动应用
        app.start();
        
        logger.info("✅ 自定义配置应用启动成功");
        
        // 停止应用
        app.stop();
    }
    
    /**
     * 示例6: Mock模式
     * 展示与Spring Boot集成的简化方式
     */
    private static void exampleMockMode() {
        logger.info("📝 示例6: Mock模式");
        
        // 创建内存队列
        MessageQueue<Envelope> inputQueue = new InMemoryMessageQueue();
        MessageQueue<Envelope> outputQueue = new InMemoryMessageQueue();
        
        // 使用流式API配置Mock模式
        MassApplication app = MassApplicationBuilder.create()
                .server(8080)
                .gateway(gateway -> gateway
                        .enabled(true)
                        .maxConnections(100)
                        .inputQueue(inputQueue)
                        .outputQueue(outputQueue))
                .engine(engine -> engine
                        .enabled(true)
                        .workerThreads(4)
                        .mockData(
                            "mock/mock_devices.json",
                            "mock/mock_tasks.json", 
                            "mock/mock_rules.json"
                        ))
                .build();
        
        // 启动应用
        app.start();
        
        // 加载Mock数据
        app.loadMockData(app.getEngine(), app.getEngine().getConfig());
        
        logger.info("✅ Mock模式应用启动成功");
        
        // 停止应用
        app.stop();
    }
} 