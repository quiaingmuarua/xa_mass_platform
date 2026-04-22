package com.xa.mass.sdk;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.sdk.model.MassTaskCreateRequest;

import java.util.Collection;

/**
 * Stable public bootstrap capabilities exposed by the SDK surface.
 *
 * <p>External bootstrap code such as dev shells, fixture loaders, or custom
 * embedders should depend on this interface instead of reaching into
 * engine/starter internals. It represents the supported runtime mutation
 * surface for adding workers, contexts, rules, and tasks after startup.
 */
public interface MassRuntimeControl {

    /**
     * Create a task through the stable SDK request contract.
     */
    Task createTask(MassTaskCreateRequest request);

    /**
     * Register an online worker with the embedded runtime.
     */
    void addWorker(Worker worker);

    /**
     * Register a worker context when the runtime uses contextual lanes.
     */
    void addWorkerContext(WorkerContext workerContext);

    /**
     * Replace the default rule set used by worker matching.
     */
    void replaceDefaultRules(Collection<RuleDefinition> rules);

    /**
     * Publish current task lifecycle events for UI/debug subscribers.
     */
    void publishTaskEvents();
}
