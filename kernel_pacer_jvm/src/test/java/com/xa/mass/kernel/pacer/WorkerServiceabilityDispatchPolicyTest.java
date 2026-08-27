package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime
        .ProbeRequestOfferStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkerServiceabilityDispatchPolicyTest {

    private static final long FLOOR = 5_000;

    @Test
    void emptyTaskBatchStopsBeforeWorkerOwners() {
        WorkerServiceabilityDispatchPolicy policy =
                new WorkerServiceabilityDispatchPolicy(
                        unused(WorkerScoreCore.class),
                        unused(WorkerResourceCatalog.class),
                        unused(WorkerServiceabilityRuntime.class),
                        () -> 10_000
                );

        assertEquals(0, policy.dispatchProbes(List.of(), config(), FLOOR));
    }

    @Test
    void derivesOneGroupAdvancesRawScorePagesAndCoolsEmptyRanges() {
        AtomicLong now = new AtomicLong(10_000);
        List<Long> hotUpperBounds = new ArrayList<>();
        List<Long> recoveryUpperBounds = new ArrayList<>();
        AtomicInteger hotPages = new AtomicInteger();
        AtomicInteger recoveryPages = new AtomicInteger();
        Map<String, WorkerScoreState> states = Map.of(
                "hot", state("hot", 100, WorkerScorePolarity.HOT_ACQUIRE,
                        4_000, 0),
                "stale-hot", state(
                        "stale-hot", 90,
                        WorkerScorePolarity.HOT_ACQUIRE, 6_000, 0
                ),
                "recovery", state(
                        "recovery", -200,
                        WorkerScorePolarity.RECOVERY_RECHECK, 7_000, 1
                )
        );
        WorkerScoreCore workerScore = proxy(
                WorkerScoreCore.class,
                (method, args) -> switch (method) {
                    case "acquirePreEpochHotCandidates" -> {
                        hotUpperBounds.add((Long) args[2]);
                        yield hotPages.getAndIncrement() == 0
                                ? List.of(
                                new WorkerScoreObservation("hot", 100),
                                new WorkerScoreObservation("stale-hot", 90)
                        ) : List.of();
                    }
                    case "acquireRecoveryRecheckCandidates" -> {
                        recoveryUpperBounds.add((Long) args[1]);
                        yield recoveryPages.getAndIncrement() == 0
                                ? List.of(new WorkerScoreObservation(
                                "recovery", -200
                        )) : List.of();
                    }
                    case "getScoreStates" -> selectedStates(
                            states,
                            castList(args[1])
                    );
                    default -> throw new AssertionError(
                            "Unexpected Worker Score call: " + method
                    );
                }
        );
        WorkerResourceCatalog workers = workerCatalog(Map.of(
                "hot", worker("hot", "group-1", "adapter-a"),
                "recovery", worker(
                        "recovery", "group-1", "adapter-b"
                )
        ));
        List<String> offers = new ArrayList<>();
        WorkerServiceabilityRuntime runtime = runtime((adapter, workerIds) -> {
            offers.add(adapter + ":" + String.join(",", workerIds));
            ProbeRequestOfferStatus status = adapter.equals("adapter-a")
                    ? ProbeRequestOfferStatus.OFFERED
                    : ProbeRequestOfferStatus.ALREADY_REQUESTED;
            return Map.of(workerIds.getFirst(), status);
        });
        WorkerServiceabilityDispatchPolicy policy =
                new WorkerServiceabilityDispatchPolicy(
                        workerScore,
                        workers,
                        runtime,
                        now::get
                );
        List<DueTaskObservation> tasks = List.of(
                task("task-1", "group-1"),
                task("task-2", "group-1")
        );

        assertEquals(1, policy.dispatchProbes(tasks, config(), FLOOR));
        assertEquals(0, policy.dispatchProbes(tasks, config(), FLOOR));
        now.set(10_001);
        assertEquals(0, policy.dispatchProbes(tasks, config(), FLOOR));

        assertEquals(List.of(0L, 90L), hotUpperBounds);
        assertEquals(List.of(0L, -200L), recoveryUpperBounds);
        assertEquals(List.of("adapter-a:hot", "adapter-b:recovery"), offers);
    }

    @Test
    void excludedEndpointUsesExactToggleThenColdParkAndNeverOffers() {
        WorkerScoreState hot = state(
                "polling-worker",
                123,
                WorkerScorePolarity.HOT_ACQUIRE,
                1_000,
                0
        );
        List<String> calls = new ArrayList<>();
        WorkerScoreCore workerScore = proxy(
                WorkerScoreCore.class,
                (method, args) -> switch (method) {
                    case "acquirePreEpochHotCandidates" -> List.of(
                            new WorkerScoreObservation("polling-worker", 123)
                    );
                    case "acquireRecoveryRecheckCandidates" -> List.of();
                    case "getScoreStates" -> Map.of("polling-worker", hot);
                    case "toggleCurrentPolarity" -> {
                        calls.add("toggle:" + args[1] + ":" + args[2]);
                        yield new WorkerScoreTransitionResult(
                                WorkerScoreTransitionStatus.TRANSITIONED,
                                -123L
                        );
                    }
                    case "exhaustRecoveryRecheck" -> {
                        calls.add("exhaust:" + args[1] + ":" + args[2]
                                + ":" + args[3]);
                        yield new WorkerScoreTransitionResult(
                                WorkerScoreTransitionStatus.TRANSITIONED,
                                -999L
                        );
                    }
                    default -> throw new AssertionError(
                            "Unexpected Worker Score call: " + method
                    );
                }
        );
        WorkerResourceCatalog workers = workerCatalog(Map.of(
                "polling-worker",
                worker("polling-worker", "group-1", "system-polling")
        ));
        WorkerServiceabilityRuntime runtime = runtime((_adapter, _workers) -> {
            throw new AssertionError("Excluded Worker must not be offered");
        });
        WorkerServiceabilityDispatchPolicy policy =
                new WorkerServiceabilityDispatchPolicy(
                        workerScore,
                        workers,
                        runtime,
                        () -> 10_000
                );

        assertEquals(0, policy.dispatchProbes(
                List.of(task("task-1", "group-1")),
                config(),
                FLOOR
        ));
        assertEquals(
                List.of(
                        "toggle:polling-worker:123",
                        "exhaust:polling-worker:-123:5"
                ),
                calls
        );
    }

    private static WorkerServiceabilityDispatchConfig config() {
        return new WorkerServiceabilityDispatchConfig(
                1_000,
                10_000,
                5,
                80,
                20,
                List.of("system-polling")
        );
    }

    private static DueTaskObservation task(
            String taskId,
            String workerGroupId
    ) {
        TaskDescriptor descriptor = new TaskDescriptor(
                taskId,
                workerGroupId,
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "1"
                )
        );
        return new DueTaskObservation(
                taskId,
                new TaskScoreState(
                        taskId,
                        1,
                        TaskScoreBand.RUNNING_VISIBLE,
                        1_000L,
                        0
                ),
                descriptor
        );
    }

    private static WorkerDescriptor worker(
            String workerId,
            String groupId,
            String adapterId
    ) {
        return new WorkerDescriptor(
                workerId,
                groupId,
                adapterId,
                Map.of(),
                Map.of()
        );
    }

    private static WorkerScoreState state(
            String workerId,
            long score,
            WorkerScorePolarity polarity,
            long timeMillis,
            int laneRank
    ) {
        return new WorkerScoreState(
                workerId,
                score,
                polarity,
                timeMillis,
                laneRank,
                0
        );
    }

    private static Map<String, WorkerScoreState> selectedStates(
            Map<String, WorkerScoreState> states,
            List<String> workerIds
    ) {
        Map<String, WorkerScoreState> selected = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            if (states.containsKey(workerId)) {
                selected.put(workerId, states.get(workerId));
            }
        }
        return selected;
    }

    private static WorkerResourceCatalog workerCatalog(
            Map<String, WorkerDescriptor> descriptors
    ) {
        return proxy(
                WorkerResourceCatalog.class,
                (method, args) -> {
                    if (!method.equals("getWorkerDescriptors")) {
                        throw new AssertionError(
                                "Unexpected Worker Catalog call: " + method
                        );
                    }
                    Map<String, WorkerDescriptor> selected =
                            new LinkedHashMap<>();
                    for (String workerId : castList(args[1])) {
                        if (descriptors.containsKey(workerId)) {
                            selected.put(workerId, descriptors.get(workerId));
                        }
                    }
                    return selected;
                }
        );
    }

    private static WorkerServiceabilityRuntime runtime(Offer offer) {
        return proxy(
                WorkerServiceabilityRuntime.class,
                (method, args) -> {
                    if (!method.equals("offerProbeRequests")) {
                        throw new AssertionError(
                                "Unexpected Serviceability call: " + method
                        );
                    }
                    return offer.apply((String) args[0], castList(args[1]));
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return (List<String>) value;
    }

    private static <T> T unused(Class<T> contract) {
        return proxy(
                contract,
                (method, _args) -> {
                    throw new AssertionError("Unexpected owner call: " + method);
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> contract, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                contract.getClassLoader(),
                new Class<?>[]{contract},
                (_proxy, method, args) -> invocation.invoke(
                        method.getName(),
                        args == null ? new Object[0] : args
                )
        );
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    @FunctionalInterface
    private interface Offer {
        Map<String, ProbeRequestOfferStatus> apply(
                String adapter,
                List<String> workerIds
        );
    }
}
