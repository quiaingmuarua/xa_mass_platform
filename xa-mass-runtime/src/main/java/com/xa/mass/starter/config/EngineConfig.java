package com.xa.mass.starter.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Runtime engine configuration.
 */
public class EngineConfig {
    private boolean enabled = true;
    private int workerThreads = 8;
    private String mockConfigPath = "mock_config.json";
    private boolean mockMode = true;
    private JsonObject mockConfigRoot;
    private String deviceConfigPath = "mock/mock_devices.json";
    private String tokenConfigPath = "mock/mock_tokens.json";
    private String taskConfigPath = "mock/mock_tasks.json";
    private String ruleConfigPath = "mock/mock_rules.json";

    private SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
    private TaskManager taskManager = new TaskManager(scheduler);
    private DeviceManager deviceManager = new DeviceManager();
    private AssignmentRecordService recordService = new AssignmentRecordService();
    private RuleManager<Map<String, Object>> ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
    private TaskMsgDispatchListener taskMsgDispatchListener;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public String getMockConfigPath() {
        return mockConfigPath;
    }

    public void setMockConfigPath(String mockConfigPath) {
        this.mockConfigPath = mockConfigPath;
    }

    public boolean isMockMode() {
        return mockMode;
    }

    public void setMockMode(boolean mockMode) {
        this.mockMode = mockMode;
    }

    public String getDeviceConfigPath() {
        return deviceConfigPath;
    }

    public void setDeviceConfigPath(String deviceConfigPath) {
        this.deviceConfigPath = deviceConfigPath;
    }

    public String getTokenConfigPath() {
        return tokenConfigPath;
    }

    public void setTokenConfigPath(String tokenConfigPath) {
        this.tokenConfigPath = tokenConfigPath;
    }

    public String getTaskConfigPath() {
        return taskConfigPath;
    }

    public void setTaskConfigPath(String taskConfigPath) {
        this.taskConfigPath = taskConfigPath;
    }

    public String getRuleConfigPath() {
        return ruleConfigPath;
    }

    public void setRuleConfigPath(String ruleConfigPath) {
        this.ruleConfigPath = ruleConfigPath;
    }

    public JsonObject getMockConfigRoot() {
        if (mockConfigRoot != null) {
            return mockConfigRoot.deepCopy();
        }

        JsonObject root = new JsonObject();
        addArrayConfig(root, "devices", deviceConfigPath);
        addArrayConfig(root, "tokens", tokenConfigPath);
        addArrayConfig(root, "tasks", taskConfigPath);
        addArrayConfig(root, "rules", ruleConfigPath);
        return root;
    }

    public void setMockConfigRoot(JsonObject mockConfigRoot) {
        this.mockConfigRoot = mockConfigRoot;
    }

    private void addArrayConfig(JsonObject root, String fieldName, String configPath) {
        try {
            String json = readConfigFile(configPath);
            root.add(fieldName, JsonParser.parseString(json).getAsJsonArray());
        } catch (Exception ignored) {
            // Optional mock inputs may be absent.
        }
    }

    /**
     * Reads config content from classpath or filesystem.
     */
    private String readConfigFile(String configPath) throws IOException {
        if (configPath.startsWith("classpath:")) {
            String classpathPath = configPath.substring("classpath:".length());
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathPath)) {
                if (is != null) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            throw new IOException("Config file not found in classpath: " + classpathPath);
        }

        try (InputStream is = getClass().getClassLoader().getResourceAsStream(configPath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        try {
            return Files.readString(Path.of(configPath));
        } catch (IOException e) {
            throw new IOException("Config file not found in classpath or file system: " + configPath, e);
        }
    }

    public SimpleTaskScheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(SimpleTaskScheduler scheduler) {
        this.scheduler = scheduler;
        if (this.taskManager == null || this.taskManager.getScheduler() != scheduler) {
            this.taskManager = new TaskManager(scheduler);
        }
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public void setTaskManager(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public DeviceManager getDeviceManager() {
        return deviceManager;
    }

    public void setDeviceManager(DeviceManager deviceManager) {
        this.deviceManager = deviceManager;
    }

    public AssignmentRecordService getRecordService() {
        return recordService;
    }

    public void setRecordService(AssignmentRecordService recordService) {
        this.recordService = recordService;
    }

    public RuleManager<Map<String, Object>> getRuleManager() {
        return ruleManager;
    }

    public void setRuleManager(RuleManager<Map<String, Object>> ruleManager) {
        this.ruleManager = ruleManager;
    }

    public TaskMsgDispatchListener getTaskMsgDispatchListener() {
        return taskMsgDispatchListener;
    }

    public void setTaskMsgDispatchListener(TaskMsgDispatchListener taskMsgDispatchListener) {
        this.taskMsgDispatchListener = taskMsgDispatchListener;
    }
}
