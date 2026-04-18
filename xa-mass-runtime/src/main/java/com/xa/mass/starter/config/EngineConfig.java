package com.xa.mass.starter.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger logger = LoggerFactory.getLogger(EngineConfig.class);
    private boolean enabled = true;
    private int workerThreads = 8;
    private String mockConfigPath = "mock_config.json";
    private boolean mockMode = true;
    private JsonObject mockConfigRoot;
    private String workerConfigPath = "mock/mock_workers.json";
    private String workerContextConfigPath = "mock/mock_worker_contexts.json";
    private String taskConfigPath = "mock/mock_tasks.json";
    private String ruleConfigPath = "mock/mock_rules.json";

    private SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
    private TaskManager taskManager = new TaskManager(scheduler);
    private WorkerManager workerManager = new WorkerManager();
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

    public String getWorkerConfigPath() {
        return workerConfigPath;
    }

    public void setWorkerConfigPath(String workerConfigPath) {
        this.workerConfigPath = workerConfigPath;
    }

    public String getWorkerContextConfigPath() {
        return workerContextConfigPath;
    }

    public void setWorkerContextConfigPath(String workerContextConfigPath) {
        this.workerContextConfigPath = workerContextConfigPath;
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
        addArrayConfig(root, "workers", workerConfigPath);
        addArrayConfig(root, "workerContexts", workerContextConfigPath);
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
        } catch (IOException e) {
            // Config file is optional; absence is expected in test/minimal environments.
            logger.debug("Optional config file not found, skipping [field={}, path={}]", fieldName, configPath);
        } catch (Exception e) {
            logger.warn("Failed to parse config file [field={}, path={}]: {}", fieldName, configPath, e.getMessage());
        }
    }

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

    public WorkerManager getWorkerManager() {
        return workerManager;
    }

    public void setWorkerManager(WorkerManager workerManager) {
        this.workerManager = workerManager;
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
