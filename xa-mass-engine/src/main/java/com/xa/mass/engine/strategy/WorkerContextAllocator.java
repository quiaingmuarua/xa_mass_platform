package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.WorkerContext;

import java.util.List;
import java.util.Map;

/**
 * WorkerContext 分配器接口
 * 为 Worker 分配可用的 WorkerContext
 */
public interface WorkerContextAllocator {

    WorkerContext allocateWorkerContext(com.xa.mass.base.model.Worker worker, Task task,
                                        List<WorkerContext> availableContexts);

    Map<com.xa.mass.base.model.Worker, WorkerContext> allocateWorkerContexts(
            Map<com.xa.mass.base.model.Worker, List<WorkerContext>> workerContextMap, Task task);

    boolean isWorkerContextSuitable(WorkerContext workerContext, Task task);

    double getWorkerContextPriority(WorkerContext workerContext, Task task);

    boolean releaseWorkerContext(WorkerContext workerContext);
}
