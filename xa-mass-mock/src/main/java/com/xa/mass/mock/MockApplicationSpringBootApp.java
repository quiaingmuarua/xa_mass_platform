package com.xa.mass.mock;

import com.google.gson.JsonObject;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
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
                // 1. 启动 Gateway (WebSocket 服务)
                startGateway(outputQueue, inputQueue);
                
                // 2. 启动 Engine (任务引擎)
                startEngine();
                
                // 3. 启动 API (Web 服务)
                log.info("✅ API 服务已通过 Spring Boot 自动启动");
                
                log.info("🎉 全链路服务启动完成！");
                
            } catch (Exception e) {
                log.error("❌ 全链路服务启动失败", e);
                throw e;
            }
        };
    }
    
    /**
     * 启动 Gateway 服务
     */
    private void startGateway(MessageQueue<Envelope> outputQueue, MessageQueue<Envelope> inputQueue) {
        log.info("🔌 启动 Gateway 服务...");
        
        try {
            // 使用开发环境默认配置
            MassApplicationConfig config = MassApplicationConfig.createDevelopment(18088, inputQueue, outputQueue);
            
            // 创建并启动 Gateway 应用
            MassApplication gatewayApp = new MassApplication(config);
            gatewayApp.start();
            
            // 获取 DispatchRuntimeContext 并注册
            DispatchRuntimeContext dispatcherContext = gatewayApp.getDispatcherContext();
            DispatcherContextRegistry.register(dispatcherContext);
            
            // 注册自定义消息处理器
            registerMessageHandlers(dispatcherContext);
            
            log.info("✅ Gateway 服务启动成功，端口: {}", config.getServerPort());
            
            // 添加关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("🛑 关闭 Gateway 服务...");
                gatewayApp.stop();
            }));
            
        } catch (Exception e) {
            log.error("❌ Gateway 服务启动失败", e);
            throw e;
        }
    }
    
    /**
     * 启动 Engine 服务
     */
    private void startEngine() {
        log.info("⚙️ 启动 Engine 服务...");
        
        try {
            // 创建引擎配置
            EngineConfig config = new EngineConfig();
            config.setTaskManager(taskManager);
            config.setDeviceManager(deviceManager);
            config.setMockMode(true);
            
            // 启动引擎
            MassEngine engine = new MassEngine(config);
            engine.start();
            
            // 加载 mock 配置并生成测试数据
            loadMockData(engine, config);
            
            // 发布任务事件
            engine.publishTaskEvents();
            log.info("✅ Engine 服务启动成功");
            
            // 添加关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("🛑 关闭 Engine 服务...");
                engine.stop();
            }));
            
        } catch (Exception e) {
            log.error("❌ Engine 服务启动失败", e);
            throw e;
        }
    }
    
    /**
     * 注册消息处理器
     */
    private void registerMessageHandlers(DispatchRuntimeContext dispatcherContext) {
        log.info("📝 注册消息处理器...");
        
        // 注册任务消息处理器
        MassMessageHandler taskHandler = msg -> {
            log.info("[Gateway Handler] 处理任务消息: {}", msg);
            return new ArrayList<>();
        };
        
        // 注册设备消息处理器
        MassMessageHandler deviceHandler = msg -> {
            log.info("[Gateway Handler] 处理设备消息: {}", msg);
            return new ArrayList<>();
        };
        
        // 注册到 MessageHandlerRegistry
        dispatcherContext.getMessageHandlerRegistry().register("mock-task", MessageType.TASK, "", taskHandler);
        dispatcherContext.getMessageHandlerRegistry().register("mock-device", MessageType.STATUS, "", deviceHandler);
        
        log.info("✅ 消息处理器注册完成");
    }
    
    /**
     * 加载 Mock 数据
     */
    private void loadMockData(MassEngine engine, EngineConfig config) {
        log.info("📊 加载 Mock 数据...");
        
        try {
            // 读取 mock 配置
            JsonObject root = config.getMockConfigRoot();
            log.info("✅ Mock 配置加载成功");
            
            // 生成设备和 Token
            if (root.has("devices")) {
                List<Token> tokenList = new ArrayList<>();
                List<Device> devices = MonkeyDeviceGenerator.generateDevices(
                    root.getAsJsonArray("devices").toString(), tokenList);
                
                log.info("📱 生成 {} 个设备和 {} 个 Token", devices.size(), tokenList.size());
                
                // 添加设备
                for (Device device : devices) {
                    engine.addDevice(device);
                    log.debug("添加设备: {} (分组: {}, 状态: {})", 
                        device.getDeviceId(), device.getGroupId(), device.getStatus());
                }
                
                // 添加 Token
                for (Token token : tokenList) {
                    engine.addToken(token);
                    log.debug("添加 Token: {} (设备: {}, 状态: {}, 渠道: {})", 
                        token.getTokenId(), token.getDeviceId(), token.getStatus(), token.getChannel());
                }
                
                // 验证设备数据
                verifyDeviceData(engine);
            }
            
            // 生成任务
            if (root.has("tasks")) {
                List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(
                    root.getAsJsonArray("tasks"));
                
                log.info("📋 生成 {} 个任务", taskDtos.size());
                
                for (TaskCreateRequestDto dto : taskDtos) {
                    engine.createTask(dto);
                    log.debug("创建任务: {} (国家: {}, 项目: {}, 数量: {})", 
                        dto.getTaskName(), dto.getCountryCode(), dto.getProject(), dto.getBatchSize());
                }
            }
            
            log.info("✅ Mock 数据加载完成");
            
        } catch (Exception e) {
            log.error("❌ Mock 数据加载失败", e);
            throw e;
        }
    }
    
    /**
     * 验证设备数据
     */
    private void verifyDeviceData(MassEngine engine) {
        DeviceManager deviceManager = engine.getDeviceManager();
        if (deviceManager != null) {
            List<Device> allDevices = deviceManager.getAllDevices();
            List<Device> usDevices = deviceManager.getDevicesByCountry("us");
            List<Device> gbDevices = deviceManager.getDevicesByCountry("gb");
            
            log.info("📊 设备数据验证 - 总计: {}, 美国: {}, 英国: {}", 
                allDevices.size(), usDevices.size(), gbDevices.size());
            
            // 显示前几个设备的详细信息
            for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
                Device device = allDevices.get(i);
                Token token = deviceManager.getToken(device.getDeviceId());
                log.info("设备 {}: ID={}, 分组={}, 状态={}, Token={}, Token状态={}", 
                    i + 1, device.getDeviceId(), device.getGroupId(), device.getStatus(),
                    token != null ? token.getTokenId() : "null",
                    token != null ? token.getStatus() : "null");
            }
        }
    }
}
