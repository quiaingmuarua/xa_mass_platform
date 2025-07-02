package com.xa.mass.starter.builder;

import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.starter.MassEngine;
import java.util.Map;

/**
 * MassEngine 构建器
 * 支持链式配置和灵活扩展
 */
public class MassEngineBuilder {
    private EngineConfig config = new EngineConfig();

    private TaskManager taskManager;
    private DeviceManager deviceManager;
    private RuleManager<Map<String, Object>> ruleManager;
    private SimpleTaskScheduler scheduler;
    private AssignmentRecordService recordService;

    private Boolean mockMode;
    private Integer workerThreads;
    private String deviceConfigPath;
    private String taskConfigPath;
    private String ruleConfigPath;
    private String mockConfigPath;

    private MassEngineBuilder() {}

    public static MassEngineBuilder create() {
        return new MassEngineBuilder();
    }

    public MassEngineBuilder config(EngineConfig config) {
        this.config = config;
        return this;
    }

    public MassEngineBuilder taskManager(TaskManager taskManager) {
        this.taskManager = taskManager;
        return this;
    }

    public MassEngineBuilder deviceManager(DeviceManager deviceManager) {
        this.deviceManager = deviceManager;
        return this;
    }

    public MassEngineBuilder ruleManager(RuleManager<Map<String, Object>> ruleManager) {
        this.ruleManager = ruleManager;
        return this;
    }

    public MassEngineBuilder scheduler(SimpleTaskScheduler scheduler) {
        this.scheduler = scheduler;
        return this;
    }

    public MassEngineBuilder recordService(AssignmentRecordService recordService) {
        this.recordService = recordService;
        return this;
    }

    public MassEngineBuilder mockMode(boolean mockMode) {
        this.mockMode = mockMode;
        return this;
    }

    public MassEngineBuilder workerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    public MassEngineBuilder mockData(String deviceConfigPath, String taskConfigPath, String ruleConfigPath) {
        this.deviceConfigPath = deviceConfigPath;
        this.taskConfigPath = taskConfigPath;
        this.ruleConfigPath = ruleConfigPath;
        return this;
    }

    public MassEngineBuilder mockData(String mockConfigPath) {
        this.mockConfigPath = mockConfigPath;
        return this;
    }

    public MassEngine build() {
        if (workerThreads != null) config.setWorkerThreads(workerThreads);
        if (mockMode != null) config.setMockMode(mockMode);
        if (deviceConfigPath != null) config.setDeviceConfigPath(deviceConfigPath);
        if (taskConfigPath != null) config.setTaskConfigPath(taskConfigPath);
        if (ruleConfigPath != null) config.setRuleConfigPath(ruleConfigPath);
        if (mockConfigPath != null) config.setMockConfigPath(mockConfigPath);
        if (scheduler != null) config.setScheduler(scheduler);
        if (taskManager != null) config.setTaskManager(taskManager);
        if (deviceManager != null) config.setDeviceManager(deviceManager);
        if (recordService != null) config.setRecordService(recordService);
        if (ruleManager != null) config.setRuleManager(ruleManager);
        return new MassEngine(config);
    }
} 