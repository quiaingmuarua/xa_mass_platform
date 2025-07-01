package com.xa.mass.starter.config;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonParser;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import java.util.Map;

/**
 * 引擎配置类
 */
public  class EngineConfig {
    private boolean enabled = true;
    private int workerThreads = 8;
    private String mockConfigPath = "mock_config.json";
    private boolean mockMode = false;
    private JsonObject mockConfigRoot;
    private String deviceConfigPath = "mock/mock_devices.json";
    private String taskConfigPath = "mock/mock_tasks.json";
    private String ruleConfigPath = "mock/mock_rules.json";

    // 引擎核心组件配置
    private SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
    private TaskManager taskManager = new TaskManager(scheduler);
    private DeviceManager deviceManager = new DeviceManager();
    private AssignmentRecordService recordService = new AssignmentRecordService();
    private RuleManager<Map<String, Object>> ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }

    public String getMockConfigPath() { return mockConfigPath; }
    public void setMockConfigPath(String mockConfigPath) { this.mockConfigPath = mockConfigPath; }

    public boolean isMockMode() { return mockMode; }
    public void setMockMode(boolean mockMode) { this.mockMode = mockMode; }

    public String getDeviceConfigPath() { return deviceConfigPath; }
    public void setDeviceConfigPath(String deviceConfigPath) { this.deviceConfigPath = deviceConfigPath; }

    public String getTaskConfigPath() { return taskConfigPath; }
    public void setTaskConfigPath(String taskConfigPath) { this.taskConfigPath = taskConfigPath; }

    public String getRuleConfigPath() { return ruleConfigPath; }
    public void setRuleConfigPath(String ruleConfigPath) { this.ruleConfigPath = ruleConfigPath; }

    public JsonObject getMockConfigRoot() {
        JsonObject root = new JsonObject();
        // 读取设备
        try {
            String deviceJson = readConfigFile(deviceConfigPath);
            root.add("devices", JsonParser.parseString(deviceJson).getAsJsonArray());
        } catch (Exception e) {
            // 处理异常或用默认
        }
        // 读取任务
        try {
            String taskJson = readConfigFile(taskConfigPath);
            root.add("tasks", JsonParser.parseString(taskJson).getAsJsonArray());
        } catch (Exception e) {
            // 处理异常或用默认
        }
        // 读取规则
        try {
            String ruleJson = readConfigFile(ruleConfigPath);
            root.add("rules", JsonParser.parseString(ruleJson).getAsJsonArray());
        } catch (Exception e) {
            // 处理异常或用默认
        }
        return root;
    }

    /**
     * 读取配置文件内容
     * 支持 classpath 路径和文件系统路径
     */
    private String readConfigFile(String configPath) throws IOException {
        // 首先尝试从 classpath 读取
        if (configPath.startsWith("classpath:")) {
            String classpathPath = configPath.substring("classpath:".length());
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            // classpath 中没有找到，抛出异常
            throw new IOException("Config file not found in classpath: " + classpathPath);
        } else {
            // 尝试从 classpath 读取（不带 classpath: 前缀）
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(configPath)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            
            // 如果 classpath 中没有找到，尝试从文件系统读取
            try {
                return Files.readString(Path.of(configPath));
            } catch (IOException e) {
                // 文件系统读取失败，抛出异常
                throw new IOException("Config file not found in classpath or file system: " + configPath, e);
            }
        }
    }

    // 默认 mock 配置
    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"devices\": " + com.xa.mass.engine.monkey.MonkeyDeviceGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + com.xa.mass.engine.monkey.MonkeyTaskGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    public void setMockConfigRoot(JsonObject mockConfigRoot) { this.mockConfigRoot = mockConfigRoot; }

    public SimpleTaskScheduler getScheduler() { return scheduler; }
    public void setScheduler(SimpleTaskScheduler scheduler) {
        this.scheduler = scheduler;
        // 级联更新 taskManager
        if (this.taskManager == null || this.taskManager.getScheduler() != scheduler) {
            this.taskManager = new TaskManager(scheduler);
        }
    }
    public TaskManager getTaskManager() { return taskManager; }
    public void setTaskManager(TaskManager taskManager) { this.taskManager = taskManager; }
    public DeviceManager getDeviceManager() { return deviceManager; }
    public void setDeviceManager(DeviceManager deviceManager) { this.deviceManager = deviceManager; }
    public AssignmentRecordService getRecordService() { return recordService; }
    public void setRecordService(AssignmentRecordService recordService) { this.recordService = recordService; }
    public RuleManager<Map<String, Object>> getRuleManager() { return ruleManager; }
    public void setRuleManager(RuleManager<Map<String, Object>> ruleManager) { this.ruleManager = ruleManager; }
}