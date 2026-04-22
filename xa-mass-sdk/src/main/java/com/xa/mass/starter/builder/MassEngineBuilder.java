package com.xa.mass.starter.builder;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;

import java.util.Map;

/**
 * Builder for {@link MassEngine}.
 */
public class MassEngineBuilder {
    private EngineConfig config = new EngineConfig();

    private TaskManager taskManager;
    private WorkerManager workerManager;
    private RuleManager<Map<String, Object>> ruleManager;
    private TaskScheduler scheduler;
    private TaskWorkerMatchingStrategy matchingStrategy;
    private AssignmentRecordService recordService;
    private MassBootstrapDataProvider bootstrapDataProvider;

    private Integer workerThreads;

    private MassEngineBuilder() {
    }

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

    public MassEngineBuilder workerManager(WorkerManager workerManager) {
        this.workerManager = workerManager;
        return this;
    }

    public MassEngineBuilder ruleManager(RuleManager<Map<String, Object>> ruleManager) {
        this.ruleManager = ruleManager;
        return this;
    }

    public MassEngineBuilder scheduler(TaskScheduler scheduler) {
        this.scheduler = scheduler;
        return this;
    }

    public MassEngineBuilder matchingStrategy(TaskWorkerMatchingStrategy matchingStrategy) {
        this.matchingStrategy = matchingStrategy;
        return this;
    }

    public MassEngineBuilder recordService(AssignmentRecordService recordService) {
        this.recordService = recordService;
        return this;
    }

    public MassEngineBuilder bootstrapDataProvider(MassBootstrapDataProvider bootstrapDataProvider) {
        this.bootstrapDataProvider = bootstrapDataProvider;
        return this;
    }

    public MassEngineBuilder workerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
        return this;
    }

    /**
     * @deprecated Mock/bootstrap data should be wired through
     * {@link #bootstrapDataProvider(MassBootstrapDataProvider)}.
     */
    @Deprecated(forRemoval = false)
    public MassEngineBuilder mockData(String workerConfigPath, String workerContextConfigPath,
                                      String taskConfigPath, String ruleConfigPath) {
        return this;
    }

    /**
     * @deprecated Mock/bootstrap data should be wired through
     * {@link #bootstrapDataProvider(MassBootstrapDataProvider)}.
     */
    @Deprecated(forRemoval = false)
    public MassEngineBuilder mockData(String mockConfigPath) {
        return this;
    }

    public MassEngine build() {
        if (workerThreads != null) config.setWorkerThreads(workerThreads);
        if (scheduler != null) config.setScheduler(scheduler);
        if (matchingStrategy != null) config.setMatchingStrategy(matchingStrategy);
        if (taskManager != null) config.setTaskManager(taskManager);
        if (workerManager != null) config.setWorkerManager(workerManager);
        if (recordService != null) config.setRecordService(recordService);
        if (ruleManager != null) config.setRuleManager(ruleManager);
        if (bootstrapDataProvider != null) config.setBootstrapDataProvider(bootstrapDataProvider);
        return new MassEngine(config);
    }
}
