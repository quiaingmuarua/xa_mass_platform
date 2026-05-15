package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.load.WorkerLoadSnapshot;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class DefaultWorkerCandidateRankerTest {

    @Test
    void ranksLowerLoadWorkerBeforeHigherLoadEquivalent() {
        DefaultWorkerCandidateRanker ranker = new DefaultWorkerCandidateRanker();
        Task task = task("task-load", null);
        WorkerMatchContext highLoad = context("worker-high", null, 4, 4);
        WorkerMatchContext lowLoad = context("worker-low", null, 0, 4);
        List<WorkerMatchContext> input = List.of(highLoad, lowLoad);

        List<WorkerMatchContext> ranked = ranker.rank(input, task);

        assertEquals("worker-low", ranked.get(0).getWorker().getWorkerId());
        assertEquals("worker-high", ranked.get(1).getWorker().getWorkerId());
        assertNotSame(input, ranked);
    }

    @Test
    void routingAffinityBreaksLoadTies() {
        DefaultWorkerCandidateRanker ranker = new DefaultWorkerCandidateRanker();
        Task task = task("task-routing", "us");
        WorkerMatchContext partialAffinity = context("worker-partial", Set.of("us-east"), 1, 4);
        WorkerMatchContext exactAffinity = context("worker-exact", Set.of("us"), 1, 4);

        List<WorkerMatchContext> ranked = ranker.rank(List.of(partialAffinity, exactAffinity), task);

        assertEquals("worker-exact", ranked.get(0).getWorker().getWorkerId());
        assertEquals("worker-partial", ranked.get(1).getWorker().getWorkerId());
    }

    private WorkerMatchContext context(String workerId,
                                       Set<String> routingTags,
                                       int activeLeaseCount,
                                       int declaredCapacity) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(Map.of());
        WorkerContext workerContext = null;
        if (routingTags != null) {
            workerContext = new WorkerContext();
            workerContext.setWorkerId(workerId);
            workerContext.setWorkerContextId("ctx-" + workerId);
            workerContext.setStatus(WorkerContextStatus.IDLE);
            workerContext.setRoutingTags(routingTags);
            workerContext.setAttributes(Map.of());
        }
        WorkerSchedulingView view = WorkerSchedulingView.from(
                worker,
                workerContext,
                WorkerReachabilityState.ONLINE,
                true,
                false,
                new WorkerLoadSnapshot(workerId, activeLeaseCount, 0, declaredCapacity)
        );
        return new WorkerMatchContext(new WorkerSchedulingCandidate(worker, workerContext, view), task("task", null));
    }

    private Task task(String taskId, String routingCode) {
        Task task = new Task();
        task.setTid(taskId);
        task.setProject("demoApp");
        task.setStatus(TaskStatus.READY);
        if (routingCode != null) {
            task.setSharedConfig(Map.of("routingCode", routingCode));
        }
        return task;
    }
}
