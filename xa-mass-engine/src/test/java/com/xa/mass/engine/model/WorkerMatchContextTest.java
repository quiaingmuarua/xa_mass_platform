package com.xa.mass.engine.model;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerReachabilityState;
import com.xa.mass.engine.load.WorkerLoadSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class WorkerMatchContextTest {

    @Test
    void contextIncludesNestedReadOnlyAttributesMaps() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(Map.of("pool", "warmup"));

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-1");
        workerContext.setWorkerId("worker-1");
        workerContext.setProject("demoApp");
        workerContext.setStatus(WorkerContextStatus.IDLE);
        workerContext.setRoutingTags(Set.of("us"));
        workerContext.setAttributes(Map.of("pool", "primary", "country", "us"));

        Task task = new Task();
        task.setTid("task-1");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker, workerContext), task);

        assertEquals(Map.of("pool", "warmup"), context.getContext().get("workerAttributes"));
        assertEquals(Map.of("pool", "primary", "country", "us"), context.getContext().get("workerContextAttributes"));
        assertEquals("ctx-1", context.getContext().get("workerSchedulingResourceId"));
        assertEquals("demoApp", context.getContext().get("workerSchedulingProject"));
        assertEquals(Set.of("us"), context.getContext().get("workerSchedulingRoutingTags"));
        assertEquals(Map.of("pool", "primary", "country", "us"), context.getContext().get("workerSchedulingAttributes"));
        assertEquals(true, context.getContext().get("hasWorkerSchedulingResource"));
        assertEquals(true, context.getContext().get("isWorkerSchedulingResourceAllocatable"));
        assertEquals(true, context.getContext().get("isWorkerSchedulingResourceAvailable"));
        assertEquals(true, context.getContext().get("isWorkerSchedulingResourceUsable"));
        assertEquals(false, context.getContext().get("isWorkerSchedulingResourceReserved"));
        assertEquals(false, context.getContext().get("isWorkerSchedulingResourceOccupied"));
        assertEquals(0, context.getContext().get("workerActiveLeaseCount"));
        assertEquals(0, context.getContext().get("workerReservedCount"));
        assertEquals(1, context.getContext().get("workerDeclaredCapacity"));
        assertEquals(0.0, context.getContext().get("workerEstimatedLoadRatio"));
        assertEquals(0, context.getContext().get("currentActiveLeaseCount"));
        assertEquals(0.0, context.getContext().get("estimatedLoadRatio"));
        assertEquals("ctx-1", context.getSchedulingView().schedulingResourceId());
        assertEquals(true, context.getContext().get("hasWorkerContext"));
        assertEquals("demoApp", context.getContext().get("workerContextProject"));
        assertEquals(0, context.getContext().get("taskTargetNumber"));
        assertEquals("us", context.getContext().get("routingCode"));
        assertEquals(true, context.getContext().get("taskHasRoutingRequirement"));
        assertEquals(true, context.getContext().get("workerSchedulingProjectMatchesTaskProject"));
        assertEquals(true, context.getContext().get("workerSchedulingMatchesRoutingCode"));
        assertEquals(true, context.getContext().get("workerContextProjectMatchesTaskProject"));
        assertEquals(true, context.getContext().get("workerContextMatchesRoutingCode"));
        assertEquals(true, context.getContext().get("isWorkerContextAvailable"));
        assertEquals(true, context.getContext().get("isWorkerContextUsable"));
        assertEquals(false, context.getContext().get("isWorkerContextReserved"));
        assertEquals(false, context.getContext().get("isWorkerContextOccupied"));
        assertFalse(context.getContext().containsKey("workerGroupIdEqualsRoutingCode"));
        assertFalse(context.getContext().containsKey("workerContextChannelMatchesRoutingCode"));
        assertFalse(context.getContext().containsKey("workerContextAttributeCountryMatchesRoutingCode"));
    }

    @Test
    void contextUsesEmptyWorkerContextAttributesWhenWorkerContextMissing() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-2");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(Map.of("pool", "worker-only"));

        Task task = new Task();
        task.setTid("task-2");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker, null), task);

        assertEquals(Map.of(), context.getContext().get("workerContextAttributes"));
        assertEquals("worker-2", context.getContext().get("workerSchedulingResourceId"));
        assertEquals(null, context.getContext().get("workerSchedulingProject"));
        assertEquals(Set.of(), context.getContext().get("workerSchedulingRoutingTags"));
        assertEquals(Map.of("pool", "worker-only"), context.getContext().get("workerSchedulingAttributes"));
        assertEquals(false, context.getContext().get("hasWorkerSchedulingResource"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceAllocatable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceAvailable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceUsable"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceReserved"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceOccupied"));
        assertFalse((Boolean) context.getContext().get("hasWorkerContext"));
        assertEquals(null, context.getContext().get("workerContextProject"));
        assertFalse((Boolean) context.getContext().get("workerSchedulingProjectMatchesTaskProject"));
        assertFalse((Boolean) context.getContext().get("workerSchedulingMatchesRoutingCode"));
        assertFalse((Boolean) context.getContext().get("workerContextProjectMatchesTaskProject"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAllocatable"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAvailable"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextUsable"));
    }

    @Test
    void statelessWorkerRoutingTagsComeFromWorkerAttributes() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-worker-attrs");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(Map.of("routingTags", "shared,us", "country", "us"));

        Task task = new Task();
        task.setTid("task-worker-attrs");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker, null), task);

        assertEquals("worker-worker-attrs", context.getContext().get("workerSchedulingResourceId"));
        assertEquals(Set.of("shared", "us"), context.getContext().get("workerSchedulingRoutingTags"));
        assertEquals("us", ((Map<?, ?>) context.getContext().get("workerSchedulingAttributes")).get("country"));
        assertEquals(true, context.getContext().get("workerSchedulingMatchesRoutingCode"));
        assertEquals(false, context.getContext().get("hasWorkerContext"));
    }

    @Test
    void contextSnapshotUsesSameSchedulingReadModelAsRuleContext() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-snapshot");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        worker.setAttributes(Map.of("routingTags", "shared,us", "country", "us"));

        Task task = new Task();
        task.setTid("task-snapshot");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("_sdk", Map.of("eventCode", "demo.dispatch")));
        task.setStatus(TaskStatus.READY);

        WorkerSchedulingCandidate candidate = candidate(worker, null);
        WorkerMatchContext context = new WorkerMatchContext(candidate, task);
        Map<String, Object> snapshot = WorkerMatchContext.contextSnapshot(candidate, task);

        assertEquals(context.getContext(), snapshot);
        assertEquals(true, snapshot.get("taskUsesEventCapability"));
        assertEquals("demo.dispatch", snapshot.get("taskEventCode"));
        assertEquals(true, snapshot.get("supportsEvent"));
        assertEquals("worker-snapshot", snapshot.get("workerSchedulingResourceId"));
        assertEquals(Set.of("shared", "us"), snapshot.get("workerSchedulingRoutingTags"));
        assertEquals(false, snapshot.get("hasWorkerContext"));
        assertNull(snapshot.get("workerContextId"));
    }

    @Test
    void reservedWorkerContextIsUsableButNotAvailableForNewAssignment() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-3");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("ctx-3");
        workerContext.setWorkerId("worker-3");
        workerContext.setStatus(WorkerContextStatus.RESERVED);
        workerContext.setRoutingTags(Set.of("us"));

        Task task = new Task();
        task.setTid("task-3");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker, workerContext), task);

        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceAllocatable"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceAvailable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceUsable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceReserved"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceOccupied"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAllocatable"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextAvailable"));
        assertTrue((Boolean) context.getContext().get("isWorkerContextUsable"));
        assertTrue((Boolean) context.getContext().get("isWorkerContextReserved"));
        assertFalse((Boolean) context.getContext().get("isWorkerContextOccupied"));
    }

    @Test
    void contextMarksRoutingRequirementFalseWhenTaskHasNoCountryConstraint() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-4");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));

        Task task = new Task();
        task.setTid("task-4");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of());
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker, null), task);

        assertEquals(false, context.getContext().get("taskHasRoutingRequirement"));
        assertEquals(false, context.getContext().get("workerContextMatchesRoutingCode"));
        assertFalse(context.getContext().containsKey("workerGroupIdEqualsRoutingCode"));
        assertFalse(context.getContext().containsKey("workerContextChannelMatchesRoutingCode"));
        assertFalse(context.getContext().containsKey("workerContextAttributeCountryMatchesRoutingCode"));
    }

    @Test
    void contextIncludesObservedWorkerLoadFields() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-5");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));

        Task task = new Task();
        task.setTid("task-5");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of());
        task.setStatus(TaskStatus.READY);

        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(
                worker,
                null,
                WorkerReachabilityState.ONLINE,
                true,
                false,
                new WorkerLoadSnapshot("worker-5", 3, 1, 2)
        );
        WorkerMatchContext context = new WorkerMatchContext(
                new WorkerSchedulingCandidate(worker, schedulingView),
                task
        );

        assertEquals(3, context.getContext().get("workerActiveLeaseCount"));
        assertEquals(1, context.getContext().get("workerReservedCount"));
        assertEquals(2, context.getContext().get("workerDeclaredCapacity"));
        assertEquals(2.0, context.getContext().get("workerEstimatedLoadRatio"));
        assertEquals(3, context.getContext().get("currentActiveLeaseCount"));
        assertEquals(2.0, context.getContext().get("estimatedLoadRatio"));
    }

    private WorkerSchedulingCandidate candidate(Worker worker, WorkerContext workerContext) {
        return new WorkerSchedulingCandidate(
                worker,
                WorkerSchedulingView.from(worker, workerContext, WorkerReachabilityState.ONLINE, true, false)
        );
    }
}
