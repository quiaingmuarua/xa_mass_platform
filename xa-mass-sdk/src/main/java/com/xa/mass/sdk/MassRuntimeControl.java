package com.xa.mass.sdk;

import com.xa.mass.storage.rule.RuleDefinition;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.model.MassTaskCommandRequest;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.TaskCommandResult;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;

import java.util.Collection;

/**
 * Stable public runtime control surface exposed by the SDK.
 *
 * <p>External bootstrap code, dev shells, fixture loaders, and custom
 * embedders should depend on this interface instead of reaching into
 * engine/starter internals. It covers the full supported mutation surface
 * for managing worker groups, workers, rules, and task lifecycle after startup.
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
    TaskShellSnapshot createTaskShell(MassTaskShellCreateRequest request);

    /**
     * Approve a NEW task, moving it to READY for dispatch.
     */
    @Deprecated(forRemoval = false)
    boolean approveTask(String taskId);

    /**
     * Reject a NEW task, moving it to BLOCKED.
     */
    @Deprecated(forRemoval = false)
    boolean rejectTask(String taskId);

    /**
     * Block a READY or RUNNING task with a hold reason.
     */
    @Deprecated(forRemoval = false)
    boolean blockTask(String taskId);

    @Deprecated(forRemoval = false)
    boolean pauseTask(String taskId);

    @Deprecated(forRemoval = false)
    boolean resumeTask(String taskId);

    @Deprecated(forRemoval = false)
    boolean cancelTask(String taskId);

    @Deprecated(forRemoval = false)
    boolean terminateTask(String taskId, String reason);

    /**
     * Append additional work items to a task using the payload contract already
     * fixed by the task shell.
     */
    int appendTaskItems(String taskId, MassTaskItemBatchAppendRequest request);

    /**
     * Seal an open-ended task so no more items can be appended.
     */
    @Deprecated(forRemoval = false)
    boolean sealTask(String taskId);

    /**
     * Current task lifecycle/governance mainline command surface.
     */
    TaskCommandResult executeTaskCommand(String taskId, MassTaskCommandRequest request);

    // --- Worker management ---

    /**
     * Register AdapterNode endpoint identity before binding it to worker groups.
     */
    void registerAdapterNode(AdapterNodeRegistration request);

    /**
     * Declare that an AdapterNode hosts a WorkerGroup before workers join.
     */
    void bindNodeGroup(NodeGroupBindingRegistration request);

    /**
     * Declare WorkerGroup capability truth before workers in that group join.
     */
    void declareWorkerGroup(WorkerGroupDeclaration request);

    /**
     * Register worker identity. The worker remains OFFLINE
     * until a transport connect/heartbeat event marks it online.
     */
    void registerWorker(WorkerRegistration request);

    // --- Rule management ---

    /**
     * Replace the default rule set used by worker matching.
     */
    void replaceDefaultRules(Collection<RuleDefinition> rules);

}
