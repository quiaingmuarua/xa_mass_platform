package com.xa.mass.starter.config;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskManagerAssignmentRuntimePort;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
import com.xa.mass.engine.TaskManagerRuntimeMaintenancePort;
import com.xa.mass.engine.TaskManagerRuntimeRecoveryPort;
import com.xa.mass.engine.TaskResultIngestFacade;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
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
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskStorage;
import com.xa.mass.storage.api.WorkerStorage;
import com.xa.mass.storage.memory.InMemoryRuleStorage;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.storage.memory.InMemoryWorkerStorage;

import java.util.Map;

/**
 * Runtime engine configuration.
 */
public class EngineConfig {

    private boolean enabled = true;
    private int workerThreads = 8;

    private TaskScheduler scheduler = new SimpleTaskScheduler();
    private TaskManager taskManager;
    private TaskCommandService taskCommandService;
    private TaskEventService taskEventService;
    private TaskQueryService taskQueryService;
    private TaskResultIngestFacade taskResultIngestFacade;
    private TaskAssignmentRuntimePort taskAssignmentRuntimePort;
    private TaskRuntimeMaintenancePort taskRuntimeMaintenancePort;
    private TaskRuntimeRecoveryPort taskRuntimeRecoveryPort;
    private TaskStorage taskStorage = new InMemoryTaskStorage();
    private TaskWorkRuntime taskWorkRuntime = new InMemoryTaskWorkRuntime();
    private TaskWorkerMatchingStrategy matchingStrategy;
    private WorkerStorage workerStorage = new InMemoryWorkerStorage();
    private WorkerManager workerManager;
    private AssignmentRecordService recordService = new AssignmentRecordService();
    private RuleStorage ruleStorage = new InMemoryRuleStorage();
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
        this.taskCommandService = source.taskCommandService;
        this.taskEventService = source.taskEventService;
        this.taskQueryService = source.taskQueryService;
        this.taskResultIngestFacade = source.taskResultIngestFacade;
        this.taskAssignmentRuntimePort = source.taskAssignmentRuntimePort;
        this.taskRuntimeMaintenancePort = source.taskRuntimeMaintenancePort;
        this.taskRuntimeRecoveryPort = source.taskRuntimeRecoveryPort;
        this.taskStorage = source.taskStorage;
        this.taskWorkRuntime = source.taskWorkRuntime;
        this.matchingStrategy = source.matchingStrategy;
        this.workerStorage = source.workerStorage;
        this.workerManager = source.workerManager;
        this.recordService = source.recordService;
        this.ruleStorage = source.ruleStorage;
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
            taskManager = new TaskManager(scheduler, getTaskStorage(), getTaskWorkRuntime());
        }
        return taskManager;
    }

    public void setTaskManager(TaskManager taskManager) {
        if (taskManager == null) {
            this.taskManager = null;
            this.taskCommandService = null;
            this.taskEventService = null;
            this.taskQueryService = null;
            this.taskResultIngestFacade = null;
            this.taskAssignmentRuntimePort = null;
            this.taskRuntimeMaintenancePort = null;
            this.taskRuntimeRecoveryPort = null;
            return;
        }
        if (taskManager.getScheduler() != scheduler) {
            throw new IllegalArgumentException("Configured taskManager must use the same scheduler as EngineConfig");
        }
        this.taskManager = taskManager;
        this.taskWorkRuntime = taskManager.getTaskWorkRuntime();
        this.taskCommandService = null;
        this.taskEventService = null;
        this.taskQueryService = null;
        this.taskResultIngestFacade = null;
        this.taskAssignmentRuntimePort = null;
        this.taskRuntimeMaintenancePort = null;
        this.taskRuntimeRecoveryPort = null;
    }

    public TaskCommandService getTaskCommandService() {
        if (taskCommandService == null) {
            taskCommandService = new TaskCommandService(getTaskManager());
        }
        return taskCommandService;
    }

    public TaskEventService getTaskEventService() {
        if (taskEventService == null) {
            taskEventService = new TaskEventService(getTaskManager());
        }
        return taskEventService;
    }

    public TaskQueryService getTaskQueryService() {
        if (taskQueryService == null) {
            taskQueryService = new TaskQueryService(getTaskManager());
        }
        return taskQueryService;
    }

    public TaskResultIngestFacade getTaskResultIngestFacade() {
        if (taskResultIngestFacade == null) {
            taskResultIngestFacade = new TaskManagerResultIngestFacade(getTaskManager());
        }
        return taskResultIngestFacade;
    }

    public TaskAssignmentRuntimePort getTaskAssignmentRuntimePort() {
        if (taskAssignmentRuntimePort == null) {
            taskAssignmentRuntimePort = new TaskManagerAssignmentRuntimePort(getTaskManager());
        }
        return taskAssignmentRuntimePort;
    }

    public TaskRuntimeMaintenancePort getTaskRuntimeMaintenancePort() {
        if (taskRuntimeMaintenancePort == null) {
            taskRuntimeMaintenancePort = new TaskManagerRuntimeMaintenancePort(getTaskManager());
        }
        return taskRuntimeMaintenancePort;
    }

    public TaskRuntimeRecoveryPort getTaskRuntimeRecoveryPort() {
        if (taskRuntimeRecoveryPort == null) {
            taskRuntimeRecoveryPort = new TaskManagerRuntimeRecoveryPort(getTaskManager());
        }
        return taskRuntimeRecoveryPort;
    }

    public TaskWorkRuntime getTaskWorkRuntime() {
        return taskWorkRuntime;
    }

    public void setTaskWorkRuntime(TaskWorkRuntime taskWorkRuntime) {
        if (taskWorkRuntime == null) {
            throw new IllegalArgumentException("taskWorkRuntime must not be null");
        }
        if (this.taskManager != null && this.taskManager.getTaskWorkRuntime() != taskWorkRuntime) {
            throw new IllegalStateException("Cannot replace taskWorkRuntime after taskManager has been configured");
        }
        this.taskWorkRuntime = taskWorkRuntime;
    }

    public TaskStorage getTaskStorage() {
        return taskStorage;
    }

    public void setTaskStorage(TaskStorage taskStorage) {
        if (taskStorage == null) {
            throw new IllegalArgumentException("taskStorage must not be null");
        }
        if (this.taskManager != null) {
            throw new IllegalStateException("Cannot replace taskStorage after taskManager has been configured");
        }
        this.taskStorage = taskStorage;
    }

    public WorkerManager getWorkerManager() {
        if (workerManager == null) {
            workerManager = new WorkerManager(getWorkerStorage());
        }
        return workerManager;
    }

    public void setWorkerManager(WorkerManager workerManager) {
        this.workerManager = workerManager;
    }

    public WorkerStorage getWorkerStorage() {
        return workerStorage;
    }

    public void setWorkerStorage(WorkerStorage workerStorage) {
        if (workerStorage == null) {
            throw new IllegalArgumentException("workerStorage must not be null");
        }
        if (this.workerManager != null) {
            throw new IllegalStateException("Cannot replace workerStorage after workerManager has been configured");
        }
        this.workerStorage = workerStorage;
    }

    public AssignmentRecordService getRecordService() {
        return recordService;
    }

    public void setRecordService(AssignmentRecordService recordService) {
        this.recordService = recordService;
    }

    public RuleManager<Map<String, Object>> getRuleManager() {
        if (ruleManager == null) {
            ruleManager = RuleManagerFactory.getDefaultRuleManager(getRuleStorage());
        }
        return ruleManager;
    }

    public void setRuleManager(RuleManager<Map<String, Object>> ruleManager) {
        this.ruleManager = ruleManager;
    }

    public RuleStorage getRuleStorage() {
        return ruleStorage;
    }

    public void setRuleStorage(RuleStorage ruleStorage) {
        if (ruleStorage == null) {
            throw new IllegalArgumentException("ruleStorage must not be null");
        }
        if (this.ruleManager != null) {
            throw new IllegalStateException("Cannot replace ruleStorage after ruleManager has been configured");
        }
        this.ruleStorage = ruleStorage;
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
