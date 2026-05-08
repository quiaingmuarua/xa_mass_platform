package com.xa.mass.sdk;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;

import java.util.Collection;

/**
 * Stable public runtime control surface exposed by the SDK.
 *
 * <p>External bootstrap code, dev shells, fixture loaders, and custom
 * embedders should depend on this interface instead of reaching into
 * engine/starter internals. It covers the full supported mutation surface
 * for managing workers, contexts, rules, and task lifecycle after startup.
 */
public interface MassRuntimeControl {

    // --- Event dispatch ---

    /**
     * Dispatch a control-plane event through the stable SDK event contract.
     */
    EventResponse dispatchEvent(EventRequest request, PrincipalContext principal);

    // --- Task creation ---

    /**
     * Create a task through the stable SDK request contract.
     */
    Task createTaskShell(MassTaskShellCreateRequest request);

    // --- Task lifecycle ---

    Task getTask(String taskId);

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
     * Append additional work items to a task using the payload contract already
     * fixed by the task shell.
     */
    int appendTaskItems(String taskId, MassTaskItemBatchAppendRequest request);

    /**
     * Seal an open-ended task so no more items can be appended.
     */
    boolean sealTask(String taskId);

    // --- Worker management ---

    /**
     * Register worker identity and capabilities. The worker remains OFFLINE
     * until a transport connect/heartbeat event marks it online.
     */
    void registerWorker(WorkerRegistration request);

    /**
     * Register an allocatable worker context. The context starts IDLE.
     */
    void registerWorkerContext(WorkerContextRegistration request);

    // --- Rule management ---

    /**
     * Replace the default rule set used by worker matching.
     */
    void replaceDefaultRules(Collection<RuleDefinition> rules);

}
