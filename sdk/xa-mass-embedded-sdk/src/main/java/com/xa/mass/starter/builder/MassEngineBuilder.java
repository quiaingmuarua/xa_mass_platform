package com.xa.mass.starter.builder;

import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskResultRuntime;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.sdk.MassBootstrapDataProvider;
import com.xa.mass.storage.api.RuleStorage;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;

/**
 * Builder for {@link MassEngine}.
 */
public class MassEngineBuilder {
    private EngineConfig config = new EngineConfig();

    private AssignmentDiagnosticRecorder recordService;
    private MassBootstrapDataProvider bootstrapDataProvider;
    private TaskShellStore taskShellStore;
    private TaskWorkRuntime taskWorkRuntime;
    private TaskResultRuntime taskResultRuntime;
    private WorkerDeclarationStore workerDeclarationStore;
    private WorkerRegistry workerRegistry;
    private WorkerScoreBandSlotRuntime workerScoreBandSlotRuntime;
    private RuleStorage ruleStorage;

    private Integer workerThreads;
    private Long taskMessageLeaseSeconds;

    private MassEngineBuilder() {
    }

    public static MassEngineBuilder create() {
        return new MassEngineBuilder();
    }

    public MassEngineBuilder config(EngineConfig config) {
        this.config = config;
        return this;
    }

    public MassEngineBuilder taskShellStore(TaskShellStore taskShellStore) {
        this.taskShellStore = taskShellStore;
        return this;
    }

    public MassEngineBuilder taskWorkRuntime(TaskWorkRuntime taskWorkRuntime) {
        this.taskWorkRuntime = taskWorkRuntime;
        return this;
    }

    public MassEngineBuilder taskResultRuntime(TaskResultRuntime taskResultRuntime) {
        this.taskResultRuntime = taskResultRuntime;
        return this;
    }

    public MassEngineBuilder workerDeclarationStore(WorkerDeclarationStore workerDeclarationStore) {
        this.workerDeclarationStore = workerDeclarationStore;
        return this;
    }

    public MassEngineBuilder workerRegistry(WorkerRegistry workerRegistry) {
        this.workerRegistry = workerRegistry;
        return this;
    }

    public MassEngineBuilder workerScoreBandSlotRuntime(WorkerScoreBandSlotRuntime workerScoreBandSlotRuntime) {
        this.workerScoreBandSlotRuntime = workerScoreBandSlotRuntime;
        return this;
    }

    public MassEngineBuilder ruleStorage(RuleStorage ruleStorage) {
        this.ruleStorage = ruleStorage;
        return this;
    }

    public MassEngineBuilder recordService(AssignmentDiagnosticRecorder recordService) {
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

    public MassEngineBuilder taskMessageLeaseSeconds(long taskMessageLeaseSeconds) {
        this.taskMessageLeaseSeconds = taskMessageLeaseSeconds;
        return this;
    }

    public MassEngine build() {
        if (workerThreads != null) config.setWorkerThreads(workerThreads);
        if (taskMessageLeaseSeconds != null) config.setTaskMessageLeaseSeconds(taskMessageLeaseSeconds);
        if (taskShellStore != null) config.setTaskShellStore(taskShellStore);
        if (taskWorkRuntime != null) config.setTaskWorkRuntime(taskWorkRuntime);
        if (taskResultRuntime != null) config.setTaskResultRuntime(taskResultRuntime);
        if (workerDeclarationStore != null) config.setWorkerDeclarationStore(workerDeclarationStore);
        if (workerRegistry != null) config.setWorkerRegistry(workerRegistry);
        if (workerScoreBandSlotRuntime != null) config.setWorkerScoreBandSlotRuntime(workerScoreBandSlotRuntime);
        if (ruleStorage != null) config.setRuleStorage(ruleStorage);
        if (recordService != null) config.setRecordService(recordService);
        if (bootstrapDataProvider != null) config.setBootstrapDataProvider(bootstrapDataProvider);
        return new MassEngine(config);
    }
}
