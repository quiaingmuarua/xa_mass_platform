package com.xa.mass.mock.starter;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;


/**
 * Mock 全链路任务分配主流程入口，支持 JSON-DSL mock 配置。
 */
@Component
@Profile("mock-engine")
public class MockTaskEngineStarter implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(MockTaskEngineStarter.class);

    // 默认 mock 配置
    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"devices\": " + MonkeyDeviceGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + MonkeyTaskGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    @Override
    public void run(String... args) throws Exception {
        EngineConfig config = new EngineConfig();
        config.setMockMode(true);
        
        // 1. 读取 mock 配置
        String configPath = config.getMockConfigPath();
        log.info("Loading mock config from: {}", configPath);
        
        // 使用 EngineConfig 的 getMockConfigRoot 方法，它会自动处理 classpath 和文件系统路径
        JsonObject root = config.getMockConfigRoot();
        log.info("Successfully loaded mock config");

        // 2. 启动引擎
        MassEngine engine = new MassEngine(config);
        engine.start();

        // 3. 注入 mock 设备和 Token
        if (root.has("devices")) {
            List<Token> tokenList = new ArrayList<>();
            List<Device> devices = MonkeyDeviceGenerator.generateDevices(root.getAsJsonArray("devices").toString(), tokenList);

            log.info("Generated {} devices and {} tokens", devices.size(), tokenList.size());

            for (Device device : devices) {
                engine.addDevice(device);
                log.debug("Added device: {} (groupId: {}, status: {})",
                        device.getDeviceId(), device.getGroupId(), device.getStatus());
            }

            for (Token token : tokenList) {
                engine.addToken(token);
                log.debug("Added token: {} (deviceId: {}, status: {}, channel: {})",
                        token.getTokenId(), token.getDeviceId(), token.getStatus(), token.getChannel());
            }

            // 验证设备添加情况
            DeviceManager deviceManager = engine.getDeviceManager();
            if (deviceManager != null) {
                List<Device> allDevices = deviceManager.getAllDevices();
                List<Device> usDevices = deviceManager.getDevicesByCountry("us");
                List<Device> gbDevices = deviceManager.getDevicesByCountry("gb");

                log.info("Device verification - Total: {}, US: {}, GB: {}",
                        allDevices.size(), usDevices.size(), gbDevices.size());

                // 显示前几个设备的详细信息
                for (int i = 0; i < Math.min(3, allDevices.size()); i++) {
                    Device device = allDevices.get(i);
                    Token token = deviceManager.getToken(device.getDeviceId());
                    log.info("Device {}: id={}, groupId={}, status={}, token={}, tokenStatus={}",
                            i + 1, device.getDeviceId(), device.getGroupId(), device.getStatus(),
                            token != null ? token.getTokenId() : "null",
                            token != null ? token.getStatus() : "null");
                }
            }
        }

        // 4. 注入 mock 任务
        if (root.has("tasks")) {
            List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(root.getAsJsonArray("tasks"));
            log.info("Generated {} tasks", taskDtos.size());

            for (TaskCreateRequestDto dto : taskDtos) {
                Task task = engine.createTask(dto);
                log.info("Created task: {} (country: {}, project: {}, initNumber: {})",
                        task.getTid(), task.getTaskCountry(), task.getProject(), task.getTaskInitNumber());
            }
        }

        // 5. 发布任务事件（worker 自动处理）
        engine.publishTaskEvents();
        log.info("Published task events for processing");
    }
} 