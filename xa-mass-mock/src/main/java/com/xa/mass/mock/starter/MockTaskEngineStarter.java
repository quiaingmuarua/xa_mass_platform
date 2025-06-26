package com.xa.mass.mock.starter;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.eventbus.enums.task.TaskStatus;
import com.xa.mass.eventbus.event.EventBusManager;
import com.xa.mass.eventbus.event.TaskReviewEvent;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.starter.EngineResourceRegistry;
import com.xa.mass.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.engine.service.AuditService;
import com.xa.mass.engine.service.AssignmentService;
import com.xa.mass.engine.service.PipelineService;
import com.xa.mass.engine.service.TaskAssignWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;


/**
 * Mock 全链路任务分配主流程入口，支持 JSON-DSL mock 配置。
 */
@Component
@Profile("mock-engine")
public class MockTaskEngineStarter implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(MockTaskEngineStarter.class);

    @Override
    public void run(String... args) throws Exception {
        EngineConfig config = new EngineConfig();
        config.setMockMode(true);
        // 1. 读取 mock 配置
        String configPath = config.getMockConfigPath();
        String jsonDsl;
        try {
            jsonDsl = Files.readString(Path.of(configPath));
            log.info("Loaded mock config from file: {}", configPath);
        } catch (IOException e) {
            log.warn("No external mock config found, using default.");
            jsonDsl = getDefaultMockConfig();
        }
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        config.setMockConfigRoot(root);

        // 2. 启动引擎
        MassEngine engine = new MassEngine(config);
        engine.start();

        // 3. 注入 mock 设备和 Token
        if (root.has("devices")) {
            List<Token> tokenList = new ArrayList<>();
            List<Device> devices = MonkeyDeviceGenerator.generateDevices(root.getAsJsonArray("devices").toString(), tokenList);
            for (Device device : devices) {
                engine.addDevice(device);
            }
            for (Token token : tokenList) {
                engine.addToken(token);
            }
        }
        // 4. 注入 mock 任务
        if (root.has("tasks")) {
            List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(root.getAsJsonArray("tasks"));
            for (TaskCreateRequestDto dto : taskDtos) {
                engine.createTask(dto);
            }
        }
        // 5. 发布任务事件（worker 自动处理）
        engine.publishTaskEvents();
    }

    // 默认 mock 配置
    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"devices\": " + MonkeyDeviceGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + MonkeyTaskGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }
} 