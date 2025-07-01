package com.xa.mass.mock;

import com.google.gson.JsonObject;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.gateway.dispatcher.DispatcherContextRegistry;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;
import com.xa.mass.gateway.queue.*;
import com.xa.mass.gateway.model.enums.MessageType;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.MassApplicationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

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
                // 组装 MassApplicationConfig
                MassApplicationConfig appConfig = MassApplicationConfig.createDevelopment(18088, inputQueue, outputQueue);
                // 组装 EngineConfig
                EngineConfig engineConfig = new EngineConfig();
                engineConfig.setTaskManager(taskManager);
                engineConfig.setDeviceManager(deviceManager);
                engineConfig.setRuleManager(ruleManager);
                engineConfig.setMockMode(true);
                // 注入 engineConfig
                appConfig.setEngineConfig(engineConfig);
                // 启动
                MassApplication app = new MassApplication(appConfig);
                app.start();
                // mock数据加载和事件发布
                app.loadMockData(app.getEngine(), engineConfig);
                app.getEngine().publishTaskEvents();
                Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
                log.info("✅ API 服务已通过 Spring Boot 自动启动");
                log.info("🎉 全链路服务启动完成！");
            } catch (Exception e) {
                log.error("❌ 全链路服务启动失败", e);
                throw e;
            }
        };
    }
}
