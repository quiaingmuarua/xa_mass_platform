package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.sdk.model.MassTaskCreateRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Stable public runtime control surface exposed by the SDK.
 *
 * <p>External bootstrap code, dev shells, fixture loaders, and custom
 * embedders should depend on this interface instead of reaching into
 * engine/starter internals. It covers the full supported mutation surface
 * for managing workers, contexts, rules, and task lifecycle after startup.
 */
public interface MassRuntimeControl {

    // --- Task creation ---

    /**
     * Create a task through the stable SDK request contract.
     */
    Task createTask(MassTaskCreateRequest request);

    // --- Task lifecycle ---

    Task getTask(String taskId);

    List<Task> getAllTasks();

    /**
     * Approve a NEW task, moving it to READY for dispatch.
     */
    boolean approveTask(String taskId);

    /**
     * Reject a NEW task, moving it to BLOCKED.
     */
    boolean rejectTask(String taskId);

    /**
     * Block a READY or RUNNING task with a hold reason.
     */
    boolean blockTask(String taskId);

    boolean pauseTask(String taskId);

    boolean resumeTask(String taskId);

    boolean cancelTask(String taskId);

    boolean terminateTask(String taskId, TaskTerminalReason reason);

    /**
     * Append additional work items to an open-ended task.
     */
    int appendTaskItems(String taskId, List<Map<String, Object>> inputs);

    /**
     * Seal an open-ended task so no more items can be appended.
     */
    boolean sealTask(String taskId);

    List<TaskMsg> getTaskMessages(String taskId);

    // --- Worker management ---

    /**
     * Register an online worker with the embedded runtime.
     */
    void addWorker(Worker worker);

    /**
     * Register a worker context when the runtime uses contextual lanes.
     */
    void addWorkerContext(WorkerContext workerContext);

    // --- Rule management ---

    /**
     * Replace the default rule set used by worker matching.
     */
    void replaceDefaultRules(Collection<RuleDefinition> rules);

    // --- Event publishing ---

    /**
     * Publish current task lifecycle events for UI/debug subscribers.
     */
    void publishTaskEvents();
}
