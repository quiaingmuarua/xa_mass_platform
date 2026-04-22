package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.sdk.model.MassTaskCreateRequest;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.sdk.model.MassTaskRequestMapper;
import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.MassApplication;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Consumer-facing runtime handle returned by the SDK facade.
 *
 * <p>The SDK artifact also carries the lower-level {@link MassApplication}
 * runtime. This wrapper keeps the common lifecycle surface explicit while
 * still allowing an escape hatch through {@link #unwrap()} for advanced
 * embedding paths.
 */
public final class MassSdkApplication {

    private final MassApplication delegate;

    MassSdkApplication(MassApplication delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void start() {
        delegate.start();
    }

    public void stop() {
        delegate.stop();
    }

    public boolean isRunning() {
        return delegate.isRunning();
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. This runtime handle
     * remains as an advanced compatibility seam for embedding paths that still
     * need direct engine access.
     */
    @Deprecated(forRemoval = false)
    public MassEngine getEngine() {
        return delegate.getEngine();
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. This manager
     * access remains as an advanced compatibility seam.
     */
    @Deprecated(forRemoval = false)
    public TaskManager getTaskManager() {
        return getEngine() != null ? getEngine().getTaskManager() : null;
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. This manager
     * access remains as an advanced compatibility seam.
     */
    @Deprecated(forRemoval = false)
    public WorkerManager getWorkerManager() {
        return getEngine() != null ? getEngine().getWorkerManager() : null;
    }

    public Task createTask(MassTaskCreateRequest request) {
        MassEngine engine = requireStartedEngine();
        return engine.createTask(toEngineRequest(request));
    }

    public Task createTask(MassTaskRequest request) {
        MassEngine engine = requireStartedEngine();
        return engine.createTask(MassTaskRequestMapper.toEngineRequest(request));
    }

    /**
     * @deprecated Prefer {@link #createTask(MassTaskCreateRequest)} so SDK callers
     * stay independent from engine DTO packages.
     */
    @Deprecated(forRemoval = false)
    public Task createTask(TaskCreateRequestDto request) {
        return requireStartedEngine().createTask(request);
    }

    public Task getTask(String taskId) {
        return requireStartedTaskManager().getTask(taskId);
    }

    public List<Task> getAllTasks() {
        return requireStartedTaskManager().getAllTasks();
    }

    public List<Task> getTasksByStatus(TaskStatus status) {
        return requireStartedTaskManager().getTasksByStatus(status);
    }

    public boolean approveTask(String taskId) {
        return requireStartedTaskManager().approveTask(taskId);
    }

    public boolean rejectTask(String taskId) {
        return requireStartedTaskManager().rejectTask(taskId);
    }

    public boolean blockTask(String taskId) {
        return requireStartedTaskManager().blockTask(taskId);
    }

    public boolean pauseTask(String taskId) {
        return requireStartedTaskManager().pauseTask(taskId);
    }

    public TaskResumeResult resumeTaskDetailed(String taskId) {
        return requireStartedTaskManager().resumeTaskDetailed(taskId);
    }

    public boolean resumeTask(String taskId) {
        return requireStartedTaskManager().resumeTask(taskId);
    }

    public boolean cancelTask(String taskId) {
        return requireStartedTaskManager().cancelTask(taskId);
    }

    public boolean terminateTask(String taskId, TaskTerminalReason reason) {
        return requireStartedTaskManager().terminateTask(taskId, reason);
    }

    public int appendTaskItems(String taskId, List<Map<String, Object>> inputs) {
        return requireStartedTaskManager().appendTaskItems(taskId, inputs);
    }

    public boolean sealTask(String taskId) {
        return requireStartedTaskManager().sealTask(taskId);
    }

    public List<TaskMsg> getTaskMessages(String taskId) {
        return requireStartedTaskManager().getTaskMessages(taskId);
    }

    public TaskStateResolutionResult resolveTaskStateFromMessages(String taskId) {
        return requireStartedTaskManager().resolveTaskStateFromMessages(taskId);
    }

    public TaskStateValidationResult validateTaskState(String taskId) {
        return requireStartedTaskManager().validateTaskState(taskId);
    }

    public void addWorker(Worker worker) {
        requireStartedEngine().addWorker(worker);
    }

    public void addWorkerContext(WorkerContext workerContext) {
        requireStartedEngine().addWorkerContext(workerContext);
    }

    public Worker getWorker(String workerId) {
        return requireStartedWorkerManager().getWorker(workerId);
    }

    public List<Worker> getAllWorkers() {
        return requireStartedWorkerManager().getAllWorkers();
    }

    public List<WorkerContext> getAllWorkerContexts() {
        return requireStartedWorkerManager().getAllWorkerContexts();
    }

    public List<WorkerContext> getWorkerContexts(String workerId) {
        return requireStartedWorkerManager().getWorkerContexts(workerId);
    }

    public WorkerContext getWorkerContextById(String workerContextId) {
        return requireStartedWorkerManager().getWorkerContextById(workerContextId);
    }

    public boolean isWorkerLocked(String workerId) {
        return requireStartedWorkerManager().isLocked(workerId);
    }

    public boolean isWorkerOnline(String workerId) {
        return requireStartedWorkerManager().isWorkerOnline(workerId);
    }

    public void loadMockData() {
        requireStartedEngine();
        delegate.loadMockData();
    }

    public void publishTaskEvents() {
        requireStartedEngine();
        delegate.publishTaskEvents();
    }

    /**
     * @deprecated Prefer the SDK facade methods on this type. The underlying
     * runtime is exposed only as an escape hatch for advanced embedding.
     */
    @Deprecated(forRemoval = false)
    public MassApplication unwrap() {
        return delegate;
    }

    private TaskCreateRequestDto toEngineRequest(MassTaskCreateRequest request) {
        Objects.requireNonNull(request, "request");
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setUserId(request.getUserId());
        dto.setProject(request.getProject());
        dto.setTaskName(request.getTaskName());
        dto.setSharedConfig(request.getSharedConfig());
        dto.setInputs(request.getInputs());
        dto.setRoutingCode(request.getRoutingCode());
        dto.setBatchSize(request.getBatchSize());
        dto.setDefaultMsgMaxRetryCount(request.getDefaultMsgMaxRetryCount());
        dto.setOpenEnded(request.isOpenEnded());
        dto.setMaxRuntimeSeconds(request.getMaxRuntimeSeconds());
        return dto;
    }

    private TaskManager requireStartedTaskManager() {
        TaskManager taskManager = requireStartedEngine().getTaskManager();
        if (taskManager == null) {
            throw new IllegalStateException("Task manager is unavailable for this SDK application");
        }
        return taskManager;
    }

    private WorkerManager requireStartedWorkerManager() {
        WorkerManager workerManager = requireStartedEngine().getWorkerManager();
        if (workerManager == null) {
            throw new IllegalStateException("Worker manager is unavailable for this SDK application");
        }
        return workerManager;
    }

    private MassEngine requireStartedEngine() {
        MassEngine engine = getEngine();
        if (engine == null) {
            throw new IllegalStateException("Mass engine is unavailable for this SDK application");
        }
        if (!engine.isRunning()) {
            throw new IllegalStateException("Mass engine has not been started");
        }
        return engine;
    }
}
