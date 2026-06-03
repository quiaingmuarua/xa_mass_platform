package com.xa.mass.engine.strategy;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TestWorkerCandidateRows;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.engine.model.WorkerMatchContext;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.runtime.scheduling.TaskDispatchIntent;
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

        List<WorkerMatchContext> ranked = ranker.rank(input, TaskDispatchIntent.fromTask(task));

        assertEquals("worker-low", ranked.get(0).getCandidateRow().workerId());
        assertEquals("worker-high", ranked.get(1).getCandidateRow().workerId());
        assertNotSame(input, ranked);
    }

    @Test
    void routingAffinityBreaksLoadTies() {
        DefaultWorkerCandidateRanker ranker = new DefaultWorkerCandidateRanker();
        Task task = task("task-routing", "us");
        WorkerMatchContext partialAffinity = context("worker-partial", Set.of("us-east"), 1, 4);
        WorkerMatchContext exactAffinity = context("worker-exact", Set.of("us"), 1, 4);

        List<WorkerMatchContext> ranked = ranker.rank(
                List.of(partialAffinity, exactAffinity),
                TaskDispatchIntent.fromTask(task)
        );

        assertEquals("worker-exact", ranked.get(0).getCandidateRow().workerId());
        assertEquals("worker-partial", ranked.get(1).getCandidateRow().workerId());
    }

    @Test
    void rankPolicyKeepsWeightsSeparateFromRankerMechanism() {
        DefaultWorkerCandidateRanker ranker =
                new DefaultWorkerCandidateRanker(new WorkerCandidateRankPolicy(0.0d, 1.0d, 0.0d));
        Task task = task("task-routing", "us");
        WorkerMatchContext lowLoadNoAffinity = context("worker-low-load", Set.of("eu"), 0, 4);
        WorkerMatchContext highLoadExactAffinity = context("worker-exact", Set.of("us"), 4, 4);

        List<WorkerMatchContext> ranked = ranker.rank(
                List.of(lowLoadNoAffinity, highLoadExactAffinity),
                TaskDispatchIntent.fromTask(task)
        );

        assertEquals("worker-exact", ranked.get(0).getCandidateRow().workerId());
        assertEquals("worker-low-load", ranked.get(1).getCandidateRow().workerId());
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
        if (routingTags != null) {
            worker.setAttributes(Map.of("routingTags", String.join(",", routingTags)));
        }
        WorkerSchedulingView view = WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker),
                WorkerReachabilityState.ONLINE,
                true,
                false,
                new WorkerLoadSnapshot(workerId, activeLeaseCount, 0, declaredCapacity)
        );
        Task task = task("task", null);
        return new WorkerMatchContext(new WorkerSchedulingCandidate(TestWorkerCandidateRows.from(worker), view),
                task,
                TaskDispatchIntent.fromTask(task));
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
