package com.xa.mass.engine.model;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerReachabilityState;
import com.xa.mass.runtime.worker.WorkerLoadSnapshot;
import com.xa.mass.engine.worker.EventBinding;
import com.xa.mass.engine.worker.WorkerGroupRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class WorkerMatchContextTest {

    @Test
    void contextUsesWorkerLevelSchedulingFields() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setAttributes(Map.of("pool", "warmup", "country", "us", "routingTags", "shared,us"));

        Task task = new Task();
        task.setTid("task-1");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task);

        assertEquals(Map.of("pool", "warmup", "country", "us", "routingTags", "shared,us"),
                context.getContext().get("workerAttributes"));
        assertFalse(context.getContext().containsKey("workerContextAttributes"));
        assertEquals("worker-1", context.getContext().get("workerSchedulingResourceId"));
        assertEquals(null, context.getContext().get("workerSchedulingProject"));
        assertEquals(Set.of("shared", "us"), context.getContext().get("workerSchedulingRoutingTags"));
        assertEquals(Map.of("pool", "warmup", "country", "us", "routingTags", "shared,us"),
                context.getContext().get("workerSchedulingAttributes"));
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
        assertEquals("worker-1", context.getSchedulingView().schedulingResourceId());
        assertEquals(0, context.getContext().get("taskTargetNumber"));
        assertEquals("us", context.getContext().get("routingCode"));
        assertEquals(true, context.getContext().get("taskHasRoutingRequirement"));
        assertEquals(false, context.getContext().get("workerSchedulingProjectMatchesTaskProject"));
        assertEquals(true, context.getContext().get("workerSchedulingMatchesRoutingCode"));
        assertNoWorkerContextRuleFields(context.getContext());
        assertFalse(context.getContext().containsKey("workerGroupIdEqualsRoutingCode"));
        assertFalse(context.getContext().containsKey("workerContextChannelMatchesRoutingCode"));
        assertFalse(context.getContext().containsKey("workerContextAttributeCountryMatchesRoutingCode"));
    }

    @Test
    void contextUsesWorkerLevelSchedulingResourceWhenWorkerContextMissing() {
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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task);

        assertEquals("worker-2", context.getContext().get("workerSchedulingResourceId"));
        assertEquals(null, context.getContext().get("workerSchedulingProject"));
        assertEquals(Set.of(), context.getContext().get("workerSchedulingRoutingTags"));
        assertEquals(Map.of("pool", "worker-only"), context.getContext().get("workerSchedulingAttributes"));
        assertEquals(true, context.getContext().get("hasWorkerSchedulingResource"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceAllocatable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceAvailable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceUsable"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceReserved"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceOccupied"));
        assertFalse((Boolean) context.getContext().get("workerSchedulingProjectMatchesTaskProject"));
        assertFalse((Boolean) context.getContext().get("workerSchedulingMatchesRoutingCode"));
        assertNoWorkerContextRuleFields(context.getContext());
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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task);

        assertEquals("worker-worker-attrs", context.getContext().get("workerSchedulingResourceId"));
        assertEquals(Set.of("shared", "us"), context.getContext().get("workerSchedulingRoutingTags"));
        assertEquals("us", ((Map<?, ?>) context.getContext().get("workerSchedulingAttributes")).get("country"));
        assertEquals(true, context.getContext().get("workerSchedulingMatchesRoutingCode"));
        assertNoWorkerContextRuleFields(context.getContext());
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

        WorkerSchedulingCandidate candidate = candidate(worker);
        WorkerMatchContext context = new WorkerMatchContext(candidate, task);
        Map<String, Object> snapshot = WorkerMatchContext.contextSnapshot(candidate, task);

        assertEquals(context.getContext(), snapshot);
        assertEquals(true, snapshot.get("taskUsesEventCapability"));
        assertEquals("demo.dispatch", snapshot.get("taskEventCode"));
        assertEquals(true, snapshot.get("supportsEvent"));
        assertEquals("worker-snapshot", snapshot.get("workerSchedulingResourceId"));
        assertEquals(Set.of("shared", "us"), snapshot.get("workerSchedulingRoutingTags"));
        assertNoWorkerContextRuleFields(snapshot);
    }

    @Test
    void workerSchedulingResourceStateComesFromLoadView() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-3");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("us");
        worker.setSupportedProjects(List.of("demoApp"));

        Task task = new Task();
        task.setTid("task-3");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us"));
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task);

        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceAllocatable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceAvailable"));
        assertTrue((Boolean) context.getContext().get("isWorkerSchedulingResourceUsable"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceReserved"));
        assertFalse((Boolean) context.getContext().get("isWorkerSchedulingResourceOccupied"));
        assertNoWorkerContextRuleFields(context.getContext());
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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task);

        assertEquals(false, context.getContext().get("taskHasRoutingRequirement"));
        assertEquals(false, context.getContext().get("workerSchedulingMatchesRoutingCode"));
        assertNoWorkerContextRuleFields(context.getContext());
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

        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(worker, WorkerReachabilityState.ONLINE,
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

    private WorkerSchedulingCandidate candidate(Worker worker) {
        WorkerGroupRecord group = groupFromWorker(worker);
        return new WorkerSchedulingCandidate(
                worker,
                WorkerSchedulingView.from(worker, group, WorkerReachabilityState.ONLINE, true, false,
                        WorkerLoadSnapshot.empty(worker.getWorkerId()))
        );
    }

    private WorkerGroupRecord groupFromWorker(Worker worker) {
        List<String> supportedProjects = worker.getSupportedProjects() == null
                ? List.of()
                : worker.getSupportedProjects();
        List<String> supportedEventCodes = worker.getSupportedEventCodes() == null
                ? List.of()
                : worker.getSupportedEventCodes();
        List<EventBinding> bindings = new ArrayList<>();
        if (!supportedProjects.isEmpty()) {
            for (String eventCode : supportedEventCodes) {
                bindings.add(EventBinding.of(eventCode, supportedProjects));
            }
        }
        return WorkerGroupRecord.builder(worker.getWorkerGroupId() == null ? "test-group" : worker.getWorkerGroupId())
                .projectCodes(supportedProjects)
                .eventBindings(bindings)
                .build();
    }

    private void assertNoWorkerContextRuleFields(Map<String, Object> context) {
        assertTrue(context.keySet().stream().noneMatch(key -> key.startsWith("workerContext")
                || key.startsWith("isWorkerContext")
                || key.equals("hasWorkerContext")));
    }
}
