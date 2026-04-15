package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;

import java.util.List;

/**
 * Worker 选择器接口
 * 按 project/国家/能力/网络等过滤 Worker
 */
public interface WorkerSelector {

    List<Worker> selectWorkers(Task task, List<Worker> availableWorkers, int requiredCount);

    boolean isWorkerSuitable(Worker worker, Task task);

    double getWorkerPriority(Worker worker, Task task);
}
