package com.xa.mass.mock;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.gateway.queue.*;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.config.EngineConfig;
// MassApplicationConfig 已删除，使用 MassApplicationBuilder
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Mock 全链路应用启动类
 * 串联 Gateway、Engine 和 API，提供完整的模拟环境
 */
@SpringBootApplication(scanBasePackages = { "com.xa.mass.mock", "com.xa.mass.api" })
public class MockApplicationSpringBootApp {

    @Autowired
    TaskManager taskManager;

    @Autowired
    DeviceManager deviceManager;

    @Autowired
    private RuleManager ruleManager;
    
    // 配置属性注入
    @Value("${mass.server.port:18088}")
    private int massServerPort;
    
    @Value("${mass.gateway.max-connections:1000}")
    private int maxConnections;
    
    @Value("${mass.engine.worker-threads:8}")
    private int workerThreads;
    
    @Value("${mass.mock.data.devices:mock/mock_devices.json}")
    private String devicesConfigPath;
    
    @Value("${mass.mock.data.tasks:mock/mock_tasks.json}")
    private String tasksConfigPath;
    
    @Value("${mass.mock.data.rules:mock/mock_rules.json}")
    private String rulesConfigPath;
    
    private static final Logger log = LoggerFactory.getLogger(MockApplicationSpringBootApp.class);
    
    public static void main(String[] args) {
        // 设置开发环境配置
        String profile = "dev";
        System.setProperty("spring.profiles.active", profile);
        String port = "8088";
        System.setProperty("server.port", port);
        
        log.info("🚀 启动 Mock 全链路应用...");
        log.info("Profile: {}, 端口: {}", profile, port);
        
        SpringApplication.run(MockApplicationSpringBootApp.class, args);
        
        log.info("✅ Mock 全链路应用启动完成");
        log.info("\n==============================");
        log.info("🌐 Web 服务地址:");
        log.info("   - 状态概览: http://localhost:{}/status", port);
        log.info("   - 任务管理: http://localhost:{}/status/tasks", port);
        log.info("   - 设备管理: http://localhost:{}/status/devices", port);
        log.info("   - 规则管理: http://localhost:{}/status/rules", port);
        log.info("   - API 文档: http://localhost:{}/doc.html", port);
        log.info("🔌 WebSocket 服务: ws://localhost:18088");
        log.info("==============================\n");
    }
    
    /**
     * 全链路启动器 - 串联 Gateway、Engine 和 API
     */
    @Bean
    @Profile("dev")
    public CommandLineRunner fullStackStarter(
            @org.springframework.beans.factory.annotation.Qualifier("outputQueue") MessageQueue<Envelope> outputQueue,
            @org.springframework.beans.factory.annotation.Qualifier("inputQueue") MessageQueue<Envelope> inputQueue) {
        return args -> {
            log.info("🔗 开始启动全链路服务...");
            try {
                // 用 MassApplicationBuilder 构建
                MassApplication app = com.xa.mass.starter.builder.MassApplicationBuilder.create()
                        .server(massServerPort)
                        .gateway(gateway -> gateway
                                .enabled(true)
                                .maxConnections(maxConnections)
                                .inputQueue(inputQueue)
                                .outputQueue(outputQueue)
                                .queueMode()
                        )
                        .engine(engine -> engine
                                .enabled(true)
                                .workerThreads(workerThreads)
                                .taskManager(taskManager)
                                .deviceManager(deviceManager)
                                .ruleManager(ruleManager)
                                .mockData(devicesConfigPath, tasksConfigPath, rulesConfigPath)
                        )
                        .build();

                // 启动应用
                app.start();
                
                // 健康检查
                if (!app.isRunning()) {
                    throw new RuntimeException("MassApplication failed to start properly");
                }
                
                // 等待组件完全启动
                Thread.sleep(1000);
                
                // 加载Mock数据
                try {
                    app.loadMockData(app.getEngine(), app.getEngine().getConfig());
                    log.info("✅ Mock数据加载成功");
                } catch (Exception e) {
                    log.warn("⚠️ Mock数据加载失败，继续启动: {}", e.getMessage());
                }
                
                // 发布任务事件
                try {
                    app.getEngine().publishTaskEvents();
                    log.info("✅ 任务事件发布成功");
                } catch (Exception e) {
                    log.warn("⚠️ 任务事件发布失败: {}", e.getMessage());
                }

                // 注册关闭钩子
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log.info("🛑 正在关闭全链路服务...");
                    try {
                        app.stop();
                        log.info("✅ 全链路服务已关闭");
                    } catch (Exception e) {
                        log.error("❌ 关闭全链路服务时发生错误", e);
                    }
                }));
                
                log.info("✅ API 服务已通过 Spring Boot 自动启动");
                log.info("🎉 全链路服务启动完成！");
                
            } catch (InterruptedException e) {
                log.error("❌ 启动过程被中断", e);
                Thread.currentThread().interrupt();
                throw new RuntimeException("Startup process was interrupted", e);
            } catch (RuntimeException e) {
                log.error("❌ 全链路服务启动失败: {}", e.getMessage(), e);
                throw e;
            } catch (Exception e) {
                log.error("❌ 全链路服务启动失败", e);
                throw new RuntimeException("Failed to start full-stack services", e);
            }
        };
    }
}
