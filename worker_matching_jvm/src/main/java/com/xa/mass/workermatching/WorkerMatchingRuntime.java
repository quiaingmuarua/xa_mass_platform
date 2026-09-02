package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerMatchRuntime;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.DemandOfferStatus;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemMatchKey;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemRuleMatchDemand;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.ItemRuleMatchEvidence;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchDemand;
import com.xa.mass.kernel.assignment.WorkerMatchRuntime.TaskRuleMatchEvidence;
import com.xa.mass.workermatching.ConstraintEvaluator.Condition;
import com.xa.mass.workermatching.WorkerMatchingCatalog.ItemRule;
import com.xa.mass.workermatching.WorkerMatchingCatalog.TaskRule;
import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One bounded resident consumer that turns persisted matching facts into
 * identity-only evidence for Kernel scheduling.
 */
public final class WorkerMatchingRuntime
        implements WorkerMatchRuntime, AutoCloseable {

    public static final int DEFAULT_DEMAND_CAPACITY = 10_000;
    public static final int DEFAULT_EVIDENCE_CAPACITY = 10_000;
    private static final System.Logger LOGGER = System.getLogger(
            WorkerMatchingRuntime.class.getName()
    );

    public enum State {
        STOPPED,
        RUNNING,
        STOPPING,
        FAILED,
        CLOSED
    }

    public record Snapshot(
            State state,
            int queuedDemands,
            int pendingDemands,
            int availableEvidence
    ) {
    }

    private sealed interface Work permits TaskWork, ItemWork {
    }

    private record TaskWork(TaskRuleMatchDemand demand) implements Work {
    }

    private record ItemWork(ItemRuleMatchDemand demand) implements Work {
    }

    private record CandidatePool(String workerGroupId, List<String> workerIds) {
    }

    private final Object lifecycleGate = new Object();
    private final WorkerMatchingCatalog catalog;
    private final ConstraintEvaluator evaluator;
    private final BlockingQueue<Work> demands;
    private final int evidenceCapacity;
    private final Set<String> pendingTasks = ConcurrentHashMap.newKeySet();
    private final Set<ItemMatchKey> pendingItems =
            ConcurrentHashMap.newKeySet();
    private final Map<String, TaskRuleMatchEvidence> taskEvidence =
            new ConcurrentHashMap<>();
    private final Map<ItemMatchKey, ItemRuleMatchEvidence> itemEvidence =
            new ConcurrentHashMap<>();
    private volatile State state = State.STOPPED;
    private volatile Thread workerThread;

    public WorkerMatchingRuntime(WorkerMatchingCatalog catalog) {
        this(
                catalog,
                DEFAULT_DEMAND_CAPACITY,
                DEFAULT_EVIDENCE_CAPACITY
        );
    }

    public WorkerMatchingRuntime(
            WorkerMatchingCatalog catalog,
            int demandCapacity,
            int evidenceCapacity
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.evaluator = new ConstraintEvaluator();
        if (demandCapacity < 1) {
            throw new IllegalArgumentException(
                    "demandCapacity must be positive"
            );
        }
        if (evidenceCapacity < 1) {
            throw new IllegalArgumentException(
                    "evidenceCapacity must be positive"
            );
        }
        this.demands = new ArrayBlockingQueue<>(demandCapacity);
        this.evidenceCapacity = evidenceCapacity;
    }

    public void start() {
        synchronized (lifecycleGate) {
            if (state == State.CLOSED) {
                throw new IllegalStateException("Worker Matching is closed");
            }
            if (state != State.STOPPED) {
                throw new IllegalStateException(
                        "Worker Matching cannot start from " + state
                );
            }
            Thread thread = Thread.ofVirtual()
                    .name("worker-matching")
                    .unstarted(this::runLoop);
            state = State.RUNNING;
            workerThread = thread;
            try {
                thread.start();
            } catch (RuntimeException | Error failure) {
                workerThread = null;
                state = State.STOPPED;
                throw failure;
            }
        }
    }

    public void stop(long timeoutMillis) {
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be positive"
            );
        }
        Thread thread;
        synchronized (lifecycleGate) {
            if (state == State.STOPPED || state == State.CLOSED) {
                return;
            }
            state = State.STOPPING;
            thread = workerThread;
            if (thread != null) {
                thread.interrupt();
            }
        }
        join(thread, timeoutMillis);
        synchronized (lifecycleGate) {
            if (state == State.STOPPING) {
                clearTransientState();
                state = State.STOPPED;
            }
        }
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                state,
                demands.size(),
                pendingTasks.size() + pendingItems.size(),
                taskEvidence.size() + itemEvidence.size()
        );
    }

    @Override
    public Map<String, DemandOfferStatus> offerTaskDemands(
            List<TaskRuleMatchDemand> offered
    ) {
        List<TaskRuleMatchDemand> bounded = boundedUniqueTaskDemands(offered);
        requireRunning();
        long now = System.currentTimeMillis();
        LinkedHashMap<String, DemandOfferStatus> result =
                new LinkedHashMap<>();
        for (TaskRuleMatchDemand demand : bounded) {
            if (demand.holdUntilMillis() <= now) {
                throw new IllegalArgumentException(
                        "Task demand must not already be expired"
                );
            }
            discardExpiredTaskEvidence(demand.taskId(), now);
            if (taskEvidence.containsKey(demand.taskId())
                    || !pendingTasks.add(demand.taskId())) {
                result.put(
                        demand.taskId(),
                        DemandOfferStatus.ALREADY_PENDING
                );
                continue;
            }
            if (!demands.offer(new TaskWork(demand))) {
                pendingTasks.remove(demand.taskId());
                result.put(demand.taskId(), DemandOfferStatus.CAPACITY);
                continue;
            }
            result.put(demand.taskId(), DemandOfferStatus.OFFERED);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Map<String, TaskRuleMatchEvidence> takeTaskEvidence(
            List<String> taskIds
    ) {
        List<String> bounded = boundedUniqueStrings(taskIds, "taskIds");
        long now = System.currentTimeMillis();
        LinkedHashMap<String, TaskRuleMatchEvidence> result =
                new LinkedHashMap<>();
        for (String taskId : bounded) {
            TaskRuleMatchEvidence evidence = taskEvidence.remove(taskId);
            if (evidence != null && evidence.holdUntilMillis() > now) {
                result.put(taskId, evidence);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Map<ItemMatchKey, DemandOfferStatus> offerItemDemands(
            List<ItemRuleMatchDemand> offered
    ) {
        List<ItemRuleMatchDemand> bounded = boundedUniqueItemDemands(offered);
        requireRunning();
        long now = System.currentTimeMillis();
        LinkedHashMap<ItemMatchKey, DemandOfferStatus> result =
                new LinkedHashMap<>();
        for (ItemRuleMatchDemand demand : bounded) {
            if (demand.holdUntilMillis() <= now) {
                throw new IllegalArgumentException(
                        "Item demand must not already be expired"
                );
            }
            discardExpiredItemEvidence(demand.key(), now);
            if (itemEvidence.containsKey(demand.key())
                    || !pendingItems.add(demand.key())) {
                result.put(
                        demand.key(),
                        DemandOfferStatus.ALREADY_PENDING
                );
                continue;
            }
            if (!demands.offer(new ItemWork(demand))) {
                pendingItems.remove(demand.key());
                result.put(demand.key(), DemandOfferStatus.CAPACITY);
                continue;
            }
            result.put(demand.key(), DemandOfferStatus.OFFERED);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Map<ItemMatchKey, ItemRuleMatchEvidence> takeItemEvidence(
            List<ItemMatchKey> keys
    ) {
        List<ItemMatchKey> bounded = boundedUniqueItemKeys(keys);
        long now = System.currentTimeMillis();
        LinkedHashMap<ItemMatchKey, ItemRuleMatchEvidence> result =
                new LinkedHashMap<>();
        for (ItemMatchKey key : bounded) {
            ItemRuleMatchEvidence evidence = itemEvidence.remove(key);
            if (evidence != null && evidence.holdUntilMillis() > now) {
                result.put(key, evidence);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void runLoop() {
        try {
            while (state == State.RUNNING) {
                Work first = demands.take();
                List<Work> batch = new ArrayList<>(MAX_BATCH_SIZE);
                batch.add(first);
                demands.drainTo(batch, MAX_BATCH_SIZE - 1);
                try {
                    processBatch(batch);
                } catch (RuntimeException failure) {
                    releasePending(batch);
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "operation=workerMatching.processBatch "
                                    + "demands=" + batch.size()
                                    + " failure="
                                    + failure.getClass().getSimpleName()
                    );
                }
            }
        } catch (InterruptedException interrupted) {
            if (state == State.RUNNING) {
                Thread.currentThread().interrupt();
                failRuntime(interrupted);
            }
        } catch (Throwable failure) {
            failRuntime(failure);
            if (failure instanceof Error error) {
                throw error;
            }
        } finally {
            synchronized (lifecycleGate) {
                if (workerThread == Thread.currentThread()) {
                    workerThread = null;
                }
            }
        }
    }

    private void processBatch(List<Work> batch) {
        List<TaskRuleMatchDemand> taskDemands = batch.stream()
                .filter(TaskWork.class::isInstance)
                .map(TaskWork.class::cast)
                .map(TaskWork::demand)
                .toList();
        List<ItemRuleMatchDemand> itemDemands = batch.stream()
                .filter(ItemWork.class::isInstance)
                .map(ItemWork.class::cast)
                .map(ItemWork::demand)
                .toList();
        if (!taskDemands.isEmpty()) {
            processTaskDemands(taskDemands);
        }
        if (!itemDemands.isEmpty()) {
            processItemDemands(itemDemands);
        }
    }

    private void processTaskDemands(List<TaskRuleMatchDemand> batch) {
        Map<String, TaskRule> rules = catalog.loadTaskRules(
                batch.stream().map(TaskRuleMatchDemand::taskId).toList()
        );
        Map<CandidatePool, Map<String, WorkerFacts>> factsByPool =
                loadCandidatePools(batch.stream()
                        .map(demand -> new CandidatePool(
                                demand.workerGroupId(),
                                demand.heldWorkerIds()
                        ))
                        .toList());
        long now = System.currentTimeMillis();
        for (TaskRuleMatchDemand demand : batch) {
            try {
                if (demand.holdUntilMillis() <= now) {
                    continue;
                }
                TaskRule rule = rules.get(demand.taskId());
                boolean usableRule = rule != null
                        && demand.workerGroupId().equals(
                                rule.workerGroupId()
                        );
                if (!usableRule) {
                    logUnavailableRule("task", demand.taskId());
                }
                List<String> matches = usableRule
                        ? matchWorkers(
                                demand.heldWorkerIds(),
                                factsByPool.get(new CandidatePool(
                                        demand.workerGroupId(),
                                        demand.heldWorkerIds()
                                )),
                                rule.allocationRule()
                        )
                        : List.of();
                publishTaskEvidence(demand, matches, now);
            } catch (IllegalArgumentException invalidRule) {
                logInvalidRule("task", demand.taskId());
                publishTaskEvidence(demand, List.of(), now);
            } finally {
                pendingTasks.remove(demand.taskId());
            }
        }
    }

    private void processItemDemands(List<ItemRuleMatchDemand> batch) {
        Map<ItemMatchKey, ItemRule> rules = catalog.loadItemRules(
                batch.stream().map(ItemRuleMatchDemand::key).toList()
        );
        Map<CandidatePool, Map<String, WorkerFacts>> factsByPool =
                loadCandidatePools(batch.stream()
                        .map(demand -> new CandidatePool(
                                demand.workerGroupId(),
                                demand.heldWorkerIds()
                        ))
                        .toList());
        long now = System.currentTimeMillis();
        for (ItemRuleMatchDemand demand : batch) {
            try {
                if (demand.holdUntilMillis() <= now) {
                    continue;
                }
                ItemRule rule = rules.get(demand.key());
                boolean usableRule = rule != null
                        && demand.workerGroupId().equals(
                                rule.workerGroupId()
                        );
                if (!usableRule) {
                    logUnavailableRule("item", demand.key().toString());
                }
                List<String> matches = usableRule
                        ? matchWorkers(
                                demand.heldWorkerIds(),
                                factsByPool.get(new CandidatePool(
                                        demand.workerGroupId(),
                                        demand.heldWorkerIds()
                                )),
                                rule.allocationRule()
                        )
                        : List.of();
                publishItemEvidence(demand, matches, now);
            } catch (IllegalArgumentException invalidRule) {
                logInvalidRule("item", demand.key().toString());
                publishItemEvidence(demand, List.of(), now);
            } finally {
                pendingItems.remove(demand.key());
            }
        }
    }

    private Map<CandidatePool, Map<String, WorkerFacts>> loadCandidatePools(
            List<CandidatePool> pools
    ) {
        LinkedHashMap<CandidatePool, Map<String, WorkerFacts>> result =
                new LinkedHashMap<>();
        for (CandidatePool pool : pools) {
            result.computeIfAbsent(pool, ignored -> catalog.loadWorkerFacts(
                    pool.workerGroupId(),
                    pool.workerIds()
            ));
        }
        return Collections.unmodifiableMap(result);
    }

    private List<String> matchWorkers(
            List<String> workerIds,
            Map<String, WorkerFacts> facts,
            Map<String, Object> allocationRule
    ) {
        List<Condition> conditions = evaluator.normalize(allocationRule);
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            WorkerFacts worker = facts.get(workerId);
            if (worker != null && evaluator.matches(
                    conditions,
                    worker.workerId(),
                    worker.workerProperties(),
                    worker.platformProperties()
            )) {
                matches.add(workerId);
            }
        }
        return List.copyOf(matches);
    }

    private void publishTaskEvidence(
            TaskRuleMatchDemand demand,
            List<String> matches,
            long now
    ) {
        if (demand.holdUntilMillis() <= now || !reserveEvidenceCapacity()) {
            return;
        }
        taskEvidence.put(demand.taskId(), new TaskRuleMatchEvidence(
                demand.taskId(),
                demand.workerGroupId(),
                matches,
                demand.holdUntilMillis()
        ));
    }

    private void publishItemEvidence(
            ItemRuleMatchDemand demand,
            List<String> matches,
            long now
    ) {
        if (demand.holdUntilMillis() <= now || !reserveEvidenceCapacity()) {
            return;
        }
        itemEvidence.put(demand.key(), new ItemRuleMatchEvidence(
                demand.key(),
                demand.workerGroupId(),
                matches,
                demand.holdUntilMillis()
        ));
    }

    private boolean reserveEvidenceCapacity() {
        discardExpiredEvidence(System.currentTimeMillis());
        return taskEvidence.size() + itemEvidence.size() < evidenceCapacity;
    }

    private void discardExpiredEvidence(long now) {
        taskEvidence.entrySet().removeIf(entry ->
                entry.getValue().holdUntilMillis() <= now);
        itemEvidence.entrySet().removeIf(entry ->
                entry.getValue().holdUntilMillis() <= now);
    }

    private void discardExpiredTaskEvidence(String taskId, long now) {
        taskEvidence.computeIfPresent(taskId, (ignored, evidence) ->
                evidence.holdUntilMillis() <= now ? null : evidence);
    }

    private void discardExpiredItemEvidence(ItemMatchKey key, long now) {
        itemEvidence.computeIfPresent(key, (ignored, evidence) ->
                evidence.holdUntilMillis() <= now ? null : evidence);
    }

    private void releasePending(List<Work> batch) {
        batch.forEach(work -> {
            if (work instanceof TaskWork task) {
                pendingTasks.remove(task.demand().taskId());
            } else if (work instanceof ItemWork item) {
                pendingItems.remove(item.demand().key());
            }
        });
    }

    private void requireRunning() {
        if (state != State.RUNNING) {
            throw new IllegalStateException(
                    "Worker Matching is not running: " + state
            );
        }
    }

    private void failRuntime(Throwable failure) {
        synchronized (lifecycleGate) {
            if (state == State.RUNNING) {
                state = State.FAILED;
                clearTransientState();
            }
        }
        LOGGER.log(
                System.Logger.Level.ERROR,
                "operation=workerMatching.run failure="
                        + failure.getClass().getSimpleName()
        );
    }

    private void clearTransientState() {
        demands.clear();
        pendingTasks.clear();
        pendingItems.clear();
        taskEvidence.clear();
        itemEvidence.clear();
    }

    @Override
    public void close() {
        Thread thread;
        synchronized (lifecycleGate) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            thread = workerThread;
            if (thread != null) {
                thread.interrupt();
            }
        }
        join(thread, 5_000);
        synchronized (lifecycleGate) {
            clearTransientState();
        }
    }

    private static void join(Thread thread, long timeoutMillis) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while stopping Worker Matching",
                    interrupted
            );
        }
        if (thread.isAlive()) {
            throw new IllegalStateException(
                    "Worker Matching did not stop within the deadline"
            );
        }
    }

    private static List<TaskRuleMatchDemand> boundedUniqueTaskDemands(
            List<TaskRuleMatchDemand> values
    ) {
        Objects.requireNonNull(values, "demands");
        if (values.isEmpty() || values.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "demands must contain 1.." + MAX_BATCH_SIZE + " entries"
            );
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (TaskRuleMatchDemand value : values) {
            Objects.requireNonNull(value, "demand");
            if (!keys.add(value.taskId())) {
                throw new IllegalArgumentException(
                        "demands must not contain duplicate Task ids"
                );
            }
        }
        return List.copyOf(values);
    }

    private static List<ItemRuleMatchDemand> boundedUniqueItemDemands(
            List<ItemRuleMatchDemand> values
    ) {
        Objects.requireNonNull(values, "demands");
        if (values.isEmpty() || values.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "demands must contain 1.." + MAX_BATCH_SIZE + " entries"
            );
        }
        LinkedHashSet<ItemMatchKey> keys = new LinkedHashSet<>();
        for (ItemRuleMatchDemand value : values) {
            Objects.requireNonNull(value, "demand");
            if (!keys.add(value.key())) {
                throw new IllegalArgumentException(
                        "demands must not contain duplicate Item keys"
                );
            }
        }
        return List.copyOf(values);
    }

    private static List<ItemMatchKey> boundedUniqueItemKeys(
            List<ItemMatchKey> values
    ) {
        Objects.requireNonNull(values, "keys");
        if (values.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "keys must contain at most " + MAX_BATCH_SIZE + " entries"
            );
        }
        LinkedHashSet<ItemMatchKey> unique = new LinkedHashSet<>();
        for (ItemMatchKey value : values) {
            Objects.requireNonNull(value, "key");
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                        "keys must not contain duplicates"
                );
            }
        }
        return List.copyOf(values);
    }

    private static List<String> boundedUniqueStrings(
            List<String> values,
            String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + MAX_BATCH_SIZE + " entries"
            );
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        name + " entries must be non-blank"
                );
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                        name + " must not contain duplicates"
                );
            }
        }
        return List.copyOf(values);
    }

    private static void logInvalidRule(String kind, String key) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "operation=workerMatching.evaluate invalidRuleKind="
                        + kind + " key=" + key
        );
    }

    private static void logUnavailableRule(String kind, String key) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "operation=workerMatching.evaluate unavailableRuleKind="
                        + kind + " key=" + key
        );
    }
}
