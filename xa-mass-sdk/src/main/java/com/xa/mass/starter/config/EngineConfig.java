package com.xa.mass.starter.config;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.sdk.MassBootstrapDataProvider;

import java.util.Map;

/**
 * Runtime engine configuration.
 */
public class EngineConfig {

    private boolean enabled = true;
    private int workerThreads = 8;

    private TaskScheduler scheduler = new SimpleTaskScheduler();
    private TaskManager taskManager;
    private TaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
    private TaskWorkerMatchingStrategy matchingStrategy;
    private WorkerManager workerManager = new WorkerManager();
    private AssignmentRecordService recordService = new AssignmentRecordService();
    private RuleManager<Map<String, Object>> ruleManager;
    private MassBootstrapDataProvider bootstrapDataProvider;
    private long assignmentRetryDelayMillis = 1000L;
    private long leaseWatchdogIntervalSeconds = 30L;
    private long taskMessageLeaseSeconds = 300L;

    public EngineConfig() {
    }

    public EngineConfig(EngineConfig source) {
        this.enabled = source.enabled;
        this.workerThreads = source.workerThreads;
        this.scheduler = source.scheduler;
        this.taskManager = source.taskManager;
        this.taskWorkRuntime = source.taskWorkRuntime;
        this.matchingStrategy = source.matchingStrategy;
        this.workerManager = source.workerManager;
        this.recordService = source.recordService;
        this.ruleManager = source.ruleManager;
        this.bootstrapDataProvider = source.bootstrapDataProvider;
        this.assignmentRetryDelayMillis = source.assignmentRetryDelayMillis;
        this.leaseWatchdogIntervalSeconds = source.leaseWatchdogIntervalSeconds;
        this.taskMessageLeaseSeconds = source.taskMessageLeaseSeconds;
    }

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

    public TaskScheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(TaskScheduler scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        if (this.taskManager != null && this.taskManager.getScheduler() != scheduler) {
            throw new IllegalStateException("Cannot replace scheduler after taskManager has been configured");
        }
        this.scheduler = scheduler;
    }

    public TaskWorkerMatchingStrategy getMatchingStrategy() {
        return matchingStrategy;
    }

    public void setMatchingStrategy(TaskWorkerMatchingStrategy matchingStrategy) {
        this.matchingStrategy = matchingStrategy;
    }

    public TaskManager getTaskManager() {
        if (taskManager == null) {
            taskManager = new TaskManager(scheduler, getTaskWorkRuntime());
        }
        return taskManager;
    }

    public void setTaskManager(TaskManager taskManager) {
        if (taskManager == null) {
            this.taskManager = null;
            return;
        }
        if (taskManager.getScheduler() != scheduler) {
            throw new IllegalArgumentException("Configured taskManager must use the same scheduler as EngineConfig");
        }
        this.taskManager = taskManager;
    }

    public TaskWorkRuntime getTaskWorkRuntime() {
        return taskWorkRuntime;
    }

    public void setTaskWorkRuntime(TaskWorkRuntime taskWorkRuntime) {
        if (taskWorkRuntime == null) {
            throw new IllegalArgumentException("taskWorkRuntime must not be null");
        }
        this.taskWorkRuntime = taskWorkRuntime;
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
        if (ruleManager == null) {
            ruleManager = RuleManagerFactory.getDefaultRuleManager();
        }
        return ruleManager;
    }

    public void setRuleManager(RuleManager<Map<String, Object>> ruleManager) {
        this.ruleManager = ruleManager;
    }

    public MassBootstrapDataProvider getBootstrapDataProvider() {
        return bootstrapDataProvider;
    }

    public void setBootstrapDataProvider(MassBootstrapDataProvider bootstrapDataProvider) {
        this.bootstrapDataProvider = bootstrapDataProvider;
    }

    public long getAssignmentRetryDelayMillis() {
        return assignmentRetryDelayMillis;
    }

    public void setAssignmentRetryDelayMillis(long assignmentRetryDelayMillis) {
        this.assignmentRetryDelayMillis = assignmentRetryDelayMillis;
    }

    public long getLeaseWatchdogIntervalSeconds() {
        return leaseWatchdogIntervalSeconds;
    }

    public void setLeaseWatchdogIntervalSeconds(long leaseWatchdogIntervalSeconds) {
        this.leaseWatchdogIntervalSeconds = leaseWatchdogIntervalSeconds;
    }

    public long getTaskMessageLeaseSeconds() {
        return taskMessageLeaseSeconds;
    }

    public void setTaskMessageLeaseSeconds(long taskMessageLeaseSeconds) {
        this.taskMessageLeaseSeconds = taskMessageLeaseSeconds;
    }
}
