package com.xa.mass.engine.model;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TestWorkerCandidateRows;
import com.xa.mass.engine.runtime.TaskRuntimeProfile;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.BackpressurePolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.ClaimPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.DispatchCadence;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.IdleClosePolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.ResultFinalityPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.RetryPolicy;
import com.xa.mass.engine.runtime.scheduling.ResolvedTaskSchedulingPolicy.WorkerResourceMode;
import com.xa.mass.engine.runtime.scheduling.TaskDispatchIntent;
import com.xa.mass.worker.runtime.evidence.WorkerGroupCapabilityView;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task, dispatchIntent(task));

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
        assertEquals(true, context.getRuleContext().get("workerSchedulingMatchesRoutingCode"));
        assertEquals(true, context.getRuleContext().get("supportsProject"));
        assertEquals(true, context.getRuleContext().get("supportsEvent"));
        assertNoRuntimeRuleEvidence(context.getRuleContext());
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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task, dispatchIntent(task));

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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task, dispatchIntent(task));

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
        TaskDispatchIntent dispatchIntent = dispatchIntent(task);
        WorkerMatchContext context = new WorkerMatchContext(candidate, task, dispatchIntent);
        Map<String, Object> snapshot = WorkerMatchContext.contextSnapshot(candidate, task, dispatchIntent);
        Map<String, Object> ruleSnapshot = WorkerMatchContext.ruleContextSnapshot(candidate, task, dispatchIntent);

        assertEquals(context.getContext(), snapshot);
        assertEquals(context.getRuleContext(), ruleSnapshot);
        assertEquals(true, snapshot.get("taskUsesEventCapability"));
        assertEquals("demo.dispatch", snapshot.get("taskEventCode"));
        assertEquals(true, snapshot.get("supportsEvent"));
        assertEquals(true, ruleSnapshot.get("taskUsesEventCapability"));
        assertEquals("demo.dispatch", ruleSnapshot.get("taskEventCode"));
        assertEquals(true, ruleSnapshot.get("supportsEvent"));
        assertEquals("worker-snapshot", snapshot.get("workerSchedulingResourceId"));
        assertEquals(Set.of("shared", "us"), snapshot.get("workerSchedulingRoutingTags"));
        assertNoRuntimeRuleEvidence(ruleSnapshot);
        assertNoWorkerContextRuleFields(snapshot);
    }

    @Test
    void fullContextOnlyKeysAreDiagnosticAndAbsentFromRuleEvaluationContext() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-diagnostic-only");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("demo.dispatch"));
        worker.setAgentVersion("1.2.3");
        worker.setAttributes(Map.of("routingTags", "shared,us", "country", "us"));

        Task task = new Task();
        task.setTid("task-diagnostic-only");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of("routingCode", "us", "_sdk", Map.of("eventCode", "demo.dispatch")));
        task.setStatus(TaskStatus.READY);
        task.setTaskTargetNumber(10);
        task.getExecutionSpec().setBatchSize(2);
        task.setMinRequiredWorkerCount(3);

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task, dispatchIntent(task));
        Set<String> diagnosticOnlyKeys = new LinkedHashSet<>(context.getContext().keySet());
        diagnosticOnlyKeys.removeAll(context.getRuleContext().keySet());

        assertEquals(Set.of(
                "taskSharedConfig",
                "taskStatus",
                "taskTargetNumber",
                "batchSize",
                "minRequiredWorkerCount",
                "appCount",
                "workerStatus",
                "transportReachability",
                "isTransportReachable",
                "agentVersion",
                "isWorkerAvailable",
                "isWorkerLocked",
                "workerActiveLeaseCount",
                "workerReservedCount",
                "workerDeclaredCapacity",
                "workerEstimatedLoadRatio",
                "currentActiveLeaseCount",
                "estimatedLoadRatio",
                "isWorkerSchedulingResourceAllocatable",
                "isWorkerSchedulingResourceAvailable",
                "isWorkerSchedulingResourceUsable",
                "isWorkerSchedulingResourceReserved",
                "isWorkerSchedulingResourceOccupied"
        ), diagnosticOnlyKeys);
        assertNoRuntimeRuleEvidence(context.getRuleContext());
    }

    @Test
    void resolvedPolicyEvidenceIsDiagnosticOnly() {
        Worker worker = new Worker();
        worker.setWorkerId("worker-policy-evidence");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));

        Task task = new Task();
        task.setTid("task-policy-evidence");
        task.setProject("demoApp");
        task.setSharedConfig(Map.of());
        task.setStatus(TaskStatus.READY);

        WorkerMatchContext context = new WorkerMatchContext(
                candidate(worker),
                task,
                dispatchIntent(task),
                policy(task)
        );

        assertEquals("SESSION", context.getContext().get("resolvedTaskPolicyPreset"));
        assertEquals("INTERACTIVE", context.getContext().get("resolvedTaskWorkloadClass"));
        assertEquals("SIGNAL_DRIVEN_DELAYED", context.getContext().get("resolvedDispatchCadence"));
        assertEquals("CAPACITY", context.getContext().get("resolvedWorkerResourceMode"));
        assertEquals(false, context.getContext().get("resolvedIdleCloseEnabled"));
        assertEquals(false, context.getContext().get("resolvedExpiredLeaseFinalizesAsFailure"));
        assertEquals(100, context.getContext().get("resolvedBackpressureMaxReadyItemsPerTask"));
        assertFalse(context.getRuleContext().containsKey("resolvedTaskPolicyPreset"));
        assertFalse(context.getRuleContext().containsKey("resolvedDispatchCadence"));
        assertFalse(context.getRuleContext().containsKey("resolvedWorkerResourceMode"));
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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task, dispatchIntent(task));

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

        WorkerMatchContext context = new WorkerMatchContext(candidate(worker), task, dispatchIntent(task));

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

        WorkerSchedulingView schedulingView = WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker),
                WorkerReachabilityState.ONLINE,
                true,
                false,
                new WorkerLoadSnapshot("worker-5", 3, 1, 2)
        );
        WorkerMatchContext context = new WorkerMatchContext(
                new WorkerSchedulingCandidate(TestWorkerCandidateRows.from(worker), schedulingView),
                task,
                dispatchIntent(task)
        );

        assertEquals(3, context.getContext().get("workerActiveLeaseCount"));
        assertEquals(1, context.getContext().get("workerReservedCount"));
        assertEquals(2, context.getContext().get("workerDeclaredCapacity"));
        assertEquals(2.0, context.getContext().get("workerEstimatedLoadRatio"));
        assertEquals(3, context.getContext().get("currentActiveLeaseCount"));
        assertEquals(2.0, context.getContext().get("estimatedLoadRatio"));
        assertNoRuntimeRuleEvidence(context.getRuleContext());
    }

    private WorkerSchedulingCandidate candidate(Worker worker) {
        WorkerGroupCapabilityView group = groupFromWorker(worker);
        return new WorkerSchedulingCandidate(
                TestWorkerCandidateRows.from(worker),
                WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker), group, WorkerReachabilityState.ONLINE,
                        true, false,
                        WorkerLoadSnapshot.empty(worker.getWorkerId()))
        );
    }

    private TaskDispatchIntent dispatchIntent(Task task) {
        return TaskDispatchIntent.fromTask(task);
    }

    private WorkerGroupCapabilityView groupFromWorker(Worker worker) {
        List<String> supportedProjects = worker.getSupportedProjects() == null
                ? List.of()
                : worker.getSupportedProjects();
        List<String> supportedEventCodes = worker.getSupportedEventCodes() == null
                ? List.of()
                : worker.getSupportedEventCodes();
        return new WorkerGroupCapabilityView(
                worker.getWorkerGroupId() == null ? "test-group" : worker.getWorkerGroupId(),
                supportedProjects,
                supportedEventCodes,
                Map.of(),
                1
        );
    }

    private ResolvedTaskSchedulingPolicy policy(Task task) {
        return new ResolvedTaskSchedulingPolicy(
                task.getTid(),
                "SESSION",
                TaskWorkloadClass.INTERACTIVE,
                TaskRuntimeProfile.DispatchLane.INTERACTIVE,
                TaskRuntimeProfile.DispatchPriority.HIGH,
                TaskRuntimeProfile.BatchPolicy.SMALL,
                TaskRuntimeProfile.LeaseProfile.SHORT,
                TaskRuntimeProfile.BackpressureClass.INTERACTIVE,
                DispatchCadence.SIGNAL_DRIVEN_DELAYED,
                WorkerResourceMode.CAPACITY,
                IdleClosePolicy.disabled(),
                new ClaimPolicy(TaskRuntimeProfile.BatchPolicy.SMALL, TaskRuntimeProfile.LeaseProfile.SHORT, 1, 30L),
                new RetryPolicy(TaskWorkloadClass.INTERACTIVE, 100L, 100L, 0L),
                ResultFinalityPolicy.session(),
                new BackpressurePolicy(TaskRuntimeProfile.BackpressureClass.INTERACTIVE, 100),
                1,
                0,
                0
        );
    }

    private void assertNoWorkerContextRuleFields(Map<String, Object> context) {
        assertTrue(context.keySet().stream().noneMatch(key -> key.startsWith("workerContext")
                || key.startsWith("isWorkerContext")
                || key.equals("hasWorkerContext")));
    }

    private void assertNoRuntimeRuleEvidence(Map<String, Object> context) {
        assertFalse(context.containsKey("isWorkerAvailable"));
        assertFalse(context.containsKey("isTransportReachable"));
        assertFalse(context.containsKey("transportReachability"));
        assertFalse(context.containsKey("isWorkerLocked"));
        assertFalse(context.containsKey("workerActiveLeaseCount"));
        assertFalse(context.containsKey("workerReservedCount"));
        assertFalse(context.containsKey("workerDeclaredCapacity"));
        assertFalse(context.containsKey("workerEstimatedLoadRatio"));
        assertFalse(context.containsKey("currentActiveLeaseCount"));
        assertFalse(context.containsKey("estimatedLoadRatio"));
        assertFalse(context.containsKey("isWorkerSchedulingResourceAllocatable"));
        assertFalse(context.containsKey("isWorkerSchedulingResourceAvailable"));
        assertFalse(context.containsKey("isWorkerSchedulingResourceUsable"));
        assertFalse(context.containsKey("isWorkerSchedulingResourceReserved"));
        assertFalse(context.containsKey("isWorkerSchedulingResourceOccupied"));
        assertFalse(context.containsKey("appCount"));
        assertFalse(context.containsKey("agentVersion"));
    }
}
