package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.delivery.ResultContextCodec;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandAppendStatus;
import com.xa.mass.kernel.pacer.dispatch.TaskExecutionMechanism.TaskItemWorkerAssignment;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.LeaseMode;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreObservation;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreTransitionResult;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreTransitionStatus;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionResult;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreTransitionStatus;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultDispatchMechanismsTest {

    @Test
    void dueActiveItemInitializationChecksAndPromotesOneBatch() {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger promotions = new AtomicInteger();
        AtomicReference<Map<String, Long>> promoted = new AtomicReference<>();
        TaskItemScoreBandCore itemScores = proxy(
                TaskItemScoreBandCore.class,
                (target, method, args) -> {
                    if ("hasDueActiveItems".equals(method.getName())) {
                        reads.incrementAndGet();
                        assertEquals(
                                List.of("initial-1", "initial-2"),
                                args[0]
                        );
                        return Map.of(
                                "initial-1", false,
                                "initial-2", true
                        );
                    }
                    throw unsupported(method.getName());
                }
        );
        TaskScoreBandCore taskScores = proxy(
                TaskScoreBandCore.class,
                (target, method, args) -> {
                    if ("promoteObservedInitialTasks".equals(
                            method.getName()
                    )) {
                        promotions.incrementAndGet();
                        @SuppressWarnings("unchecked")
                        Map<String, Long> scores =
                                (Map<String, Long>) args[0];
                        promoted.set(Map.copyOf(scores));
                        return Map.of("initial-2", transitioned(201L));
                    }
                    throw unsupported(method.getName());
                }
        );
        LinkedHashMap<String, Long> scores = new LinkedHashMap<>();
        scores.put("initial-1", 101L);
        scores.put("initial-2", 102L);

        new DueActiveItemInitializationCheck(
                itemScores,
                taskScores
        ).check(scores);

        assertEquals(1, reads.get());
        assertEquals(1, promotions.get());
        assertEquals(Map.of("initial-2", 102L), promoted.get());
    }

    @Test
    void candidateLeaseReloadsCanonicalDescriptorAfterExactCas() {
        WorkerDescriptor before = worker("worker-1", "east");
        WorkerDescriptor after = worker("worker-1", "west");
        AtomicInteger descriptorReads = new AtomicInteger();
        WorkerScoreCore workerScores = proxy(
                WorkerScoreCore.class,
                (target, method, args) -> switch (method.getName()) {
                    case "acquireHotAcquireCandidates" ->
                            Map.of("worker-1", 111L);
                    case "acquireObservedHotScoreLeases" -> Map.of(
                            "worker-1",
                            new WorkerScoreTransitionResult(
                                    WorkerScoreTransitionStatus.TRANSITIONED,
                                    222L
                            )
                    );
                    default -> throw unsupported(method.getName());
                }
        );
        WorkerResourceCatalog workerCatalog = proxy(
                WorkerResourceCatalog.class,
                (target, method, args) -> {
                    if ("getWorkerDescriptors".equals(method.getName())) {
                        return Map.of(
                                "worker-1",
                                descriptorReads.getAndIncrement() == 0
                                        ? before
                                        : after
                        );
                    }
                    throw unsupported(method.getName());
                }
        );
        CandidateWorkerCache cache = proxy(
                CandidateWorkerCache.class,
                (target, method, args) -> Map.of()
        );
        DefaultWorkerCandidateMechanism mechanism =
                new DefaultWorkerCandidateMechanism(
                        cache,
                        workerScores,
                        workerCatalog
                );

        List<WorkerCandidateObservation> observed = mechanism.observeHot(
                "group-1",
                null,
                100
        );
        List<WorkerCandidateObservation> leased = mechanism.leaseSelected(
                "group-1",
                observed,
                5_000,
                LeaseMode.ACQUIRE
        );

        assertEquals("east", observed.get(0).descriptor()
                .workerProperties().get("region"));
        assertEquals("west", leased.get(0).descriptor()
                .workerProperties().get("region"));
        assertEquals(
                "WorkerCandidateReference[opaque]",
                leased.get(0).reference().toString()
        );
    }

    @Test
    void taskExecutionVerifiesLeaseClaimsItemAndPublishesCorrelation() {
        TaskDescriptor descriptor = task("task-1");
        TaskItem item = new TaskItem(
                "message-1",
                "event.demo",
                0,
                Map.of("value", 1),
                0,
                null,
                Map.of()
        );
        TaskScoreBandCore taskScores = proxy(
                TaskScoreBandCore.class,
                (target, method, args) -> {
                    if ("rewriteSameBandTimeMillis".equals(method.getName())) {
                        return transitioned(999L);
                    }
                    throw unsupported(method.getName());
                }
        );
        TaskItemScoreBandCore itemScores = proxy(
                TaskItemScoreBandCore.class,
                (target, method, args) -> switch (method.getName()) {
                    case "acquireItemScoreCandidates" -> Map.of(
                            "message-1",
                            new TaskItemScoreObservation(333L, 1)
                    );
                    case "rewriteObservedItemScores" -> Map.of(
                            "message-1",
                            new TaskItemScoreTransitionResult(
                                    TaskItemScoreTransitionStatus.TRANSITIONED,
                                    444L
                            )
                    );
                    default -> throw unsupported(method.getName());
                }
        );
        WorkerScoreCore workerScores = proxy(
                WorkerScoreCore.class,
                (target, method, args) -> {
                    if ("renewActiveHotScoreLeases".equals(method.getName())) {
                        return Map.of(
                                "worker-1",
                                new WorkerScoreTransitionResult(
                                        WorkerScoreTransitionStatus.NOOP,
                                        555L
                                )
                        );
                    }
                    throw unsupported(method.getName());
                }
        );
        TaskRuntime taskRuntime = proxy(
                TaskRuntime.class,
                (target, method, args) -> {
                    if ("loadTaskItems".equals(method.getName())) {
                        return Map.of("message-1", item);
                    }
                    throw unsupported(method.getName());
                }
        );
        AtomicReference<DeliveryCommand> published = new AtomicReference<>();
        WorkerCommandRuntime commands = proxy(
                WorkerCommandRuntime.class,
                (target, method, args) -> {
                    if ("appendWorkerCommands".equals(method.getName())) {
                        @SuppressWarnings("unchecked")
                        Map<String, DeliveryCommand> byWorker =
                                (Map<String, DeliveryCommand>) args[1];
                        published.set(byWorker.get("worker-1"));
                        return Map.of(
                                "worker-1",
                                WorkerCommandAppendStatus.APPENDED
                        );
                    }
                    throw unsupported(method.getName());
                }
        );
        ResultContextCodec codec = new ResultContextCodec();
        DefaultTaskExecutionMechanism mechanism =
                new DefaultTaskExecutionMechanism(
                        taskScores,
                        itemScores,
                        workerScores,
                        taskRuntime,
                        commands,
                        codec
                );
        DueTaskObservation task = new DueTaskObservation(
                "task-1",
                new TaskSchedulingReference("task-1", 777L),
                descriptor
        );
        var itemObservation = mechanism.observeTaskItems("task-1", 100)
                .get(0);
        WorkerCandidateObservation worker =
                new WorkerCandidateObservation(
                        "worker-1",
                        "group-1",
                        worker("worker-1", "east"),
                        new WorkerCandidateReference(
                                "group-1",
                                "worker-1",
                                555L
                        )
                );

        assertEquals(1, mechanism.dispatch(
                task,
                List.of(new TaskItemWorkerAssignment(
                        itemObservation,
                        worker
                )),
                5_000
        ));
        mechanism.onDispatchAttemptFinished(task, 1_000);

        assertNotNull(published.get());
        assertTrue(codec.decodeForRouting(published.get().forward())
                .isPresent());
    }

    @Test
    void taskExecutionRejectsDuplicateWorkerBeforeAnyOwnerMutation() {
        AtomicInteger workerScoreCalls = new AtomicInteger();
        DefaultTaskExecutionMechanism mechanism =
                new DefaultTaskExecutionMechanism(
                        proxy(
                                TaskScoreBandCore.class,
                                (target, method, args) -> {
                                    throw unsupported(method.getName());
                                }
                        ),
                        proxy(
                                TaskItemScoreBandCore.class,
                                (target, method, args) -> {
                                    throw unsupported(method.getName());
                                }
                        ),
                        proxy(
                                WorkerScoreCore.class,
                                (target, method, args) -> {
                                    workerScoreCalls.incrementAndGet();
                                    throw unsupported(method.getName());
                                }
                        ),
                        proxy(
                                TaskRuntime.class,
                                (target, method, args) -> {
                                    throw unsupported(method.getName());
                                }
                        ),
                        proxy(
                                WorkerCommandRuntime.class,
                                (target, method, args) -> {
                                    throw unsupported(method.getName());
                                }
                        ),
                        new ResultContextCodec()
                );
        DueTaskObservation task = new DueTaskObservation(
                "task-1",
                new TaskSchedulingReference("task-1", 777L),
                task("task-1")
        );
        WorkerCandidateObservation worker =
                new WorkerCandidateObservation(
                        "worker-1",
                        "group-1",
                        worker("worker-1", "east"),
                        new WorkerCandidateReference(
                                "group-1",
                                "worker-1",
                                555L
                        )
                );
        TaskItemWorkerAssignment first = new TaskItemWorkerAssignment(
                itemObservation("task-1", "message-1"),
                worker
        );
        TaskItemWorkerAssignment second = new TaskItemWorkerAssignment(
                itemObservation("task-1", "message-2"),
                worker
        );

        assertThrows(IllegalArgumentException.class, () ->
                mechanism.dispatch(task, List.of(first, second), 5_000)
        );
        assertEquals(0, workerScoreCalls.get());
    }

    @Test
    void serviceabilitySweepRechecksExactScoreBeforeProbeOffer() {
        WorkerScoreCore workerScores = proxy(
                WorkerScoreCore.class,
                (target, method, args) -> switch (method.getName()) {
                    case "acquirePreEpochHotCandidates" -> List.of(
                            new WorkerScoreCore.WorkerScoreObservation(
                                    "worker-1",
                                    123L
                            )
                    );
                    case "getScoreStates" -> Map.of(
                            "worker-1",
                            new WorkerScoreState(
                                    "worker-1",
                                    123L,
                                    WorkerScorePolarity.HOT_ACQUIRE,
                                    100,
                                    0,
                                    0
                            )
                    );
                    default -> throw unsupported(method.getName());
                }
        );
        WorkerResourceCatalog catalog = proxy(
                WorkerResourceCatalog.class,
                (target, method, args) -> {
                    if ("getWorkerDescriptors".equals(method.getName())) {
                        return Map.of("worker-1", worker("worker-1", "east"));
                    }
                    throw unsupported(method.getName());
                }
        );
        DefaultWorkerServiceabilityDispatchMechanism mechanism =
                new DefaultWorkerServiceabilityDispatchMechanism(
                        workerScores,
                        catalog
                );

        var page = mechanism.observePreEpochHot(
                "group-1",
                1_000,
                WorkerSweepCursor.start(),
                80
        );
        var rechecked = mechanism.recheck(
                "group-1",
                page.candidates()
        );

        assertEquals(1, rechecked.size());
        assertEquals("WorkerSweepCursor[opaque]", page.nextCursor().toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            Class<T> type,
            java.lang.reflect.InvocationHandler handler
    ) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                handler
        );
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(method);
    }

    private static TaskScoreTransitionResult transitioned(long score) {
        return new TaskScoreTransitionResult(
                TaskScoreTransitionStatus.TRANSITIONED,
                score
        );
    }

    private static TaskExecutionMechanism.TaskItemObservation itemObservation(
            String taskId,
            String messageId
    ) {
        return new TaskExecutionMechanism.TaskItemObservation(
                messageId,
                1,
                new TaskItem(
                        messageId,
                        "event.demo",
                        0,
                        Map.of(),
                        0,
                        null,
                        Map.of()
                ),
                new TaskItemReference(taskId, messageId, 333L)
        );
    }

    private static TaskDescriptor task(String taskId) {
        return new TaskDescriptor(
                taskId,
                "group-1",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "1"
                )
        );
    }

    private static WorkerDescriptor worker(String workerId, String region) {
        return new WorkerDescriptor(
                workerId,
                "group-1",
                "adapter-1",
                Map.of("region", region),
                Map.of()
        );
    }
}
