package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand;
import com.xa.mass.kernel.assignment.TaskRuleMatchDemand.TaskCandidateNeed;
import com.xa.mass.kernel.assignment.WorkerMatchQueue;
import com.xa.mass.workermatching.ConstraintEvaluator.Condition;
import com.xa.mass.workermatching.WorkerMatchingCatalog.CandidateRule;
import com.xa.mass.workermatching.WorkerMatchingCatalog.WorkerFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Resolves ordered Candidate demands into bounded Candidate Cache entries. */
public final class WorkerMatchingRuntime implements AutoCloseable {

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
            int queuedDemands
    ) {
    }

    private final Object lifecycleGate = new Object();
    private final WorkerMatchingCatalog catalog;
    private final CandidateWorkerCache candidateCache;
    private final ConstraintEvaluator evaluator;
    private final WorkerMatchQueue matchQueue;
    private volatile State state = State.STOPPED;
    private volatile Thread workerThread;

    public WorkerMatchingRuntime(
            WorkerMatchingCatalog catalog,
            CandidateWorkerCache candidateCache,
            WorkerMatchQueue matchQueue
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.candidateCache = Objects.requireNonNull(
                candidateCache,
                "candidateCache"
        );
        this.evaluator = new ConstraintEvaluator();
        this.matchQueue = Objects.requireNonNull(
                matchQueue,
                "matchQueue"
        );
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
                matchQueue.size()
        );
    }

    private void runLoop() {
        try {
            while (state == State.RUNNING) {
                TaskRuleMatchDemand demand = matchQueue.consume();
                try {
                    processDemand(demand);
                } catch (RuntimeException failure) {
                    LOGGER.log(
                            System.Logger.Level.WARNING,
                            "operation=workerMatching.processDemand "
                                    + "workerGroupId="
                                    + demand.workerGroupId()
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

    private void processDemand(TaskRuleMatchDemand demand) {
        if (demand.holdUntilMillis() <= System.currentTimeMillis()) {
            return;
        }
        List<String> candidateIds = demand.orderedTaskNeeds().stream()
                .map(TaskCandidateNeed::candidateId)
                .toList();
        Map<String, CandidateRule> rules = catalog.loadCandidateRules(
                candidateIds
        );
        Map<String, WorkerFacts> facts = catalog.loadWorkerFacts(
                demand.workerGroupId(),
                List.copyOf(demand.heldWorkerLeaseScores().keySet())
        );
        LinkedHashMap<String, Long> available = new LinkedHashMap<>(
                demand.heldWorkerLeaseScores()
        );
        for (TaskCandidateNeed need : demand.orderedTaskNeeds()) {
            if (available.isEmpty()
                    || demand.holdUntilMillis()
                            <= System.currentTimeMillis()) {
                return;
            }
            CandidateRule rule = rules.get(need.candidateId());
            if (rule == null
                    || !demand.workerGroupId().equals(rule.workerGroupId())) {
                logUnavailableRule(need.candidateId());
                continue;
            }
            List<CandidateWorkerEntry> matches;
            try {
                matches = matchCandidates(available, facts, rule);
            } catch (IllegalArgumentException invalidRule) {
                logInvalidRule(need.candidateId());
                continue;
            }
            if (matches.isEmpty()) {
                continue;
            }
            List<String> accepted = candidateCache.appendCandidateWorkers(
                    need.candidateId(),
                    need.maximumCandidateWorkers(),
                    matches,
                    demand.holdUntilMillis()
            );
            accepted.forEach(available::remove);
        }
    }

    private List<CandidateWorkerEntry> matchCandidates(
            LinkedHashMap<String, Long> available,
            Map<String, WorkerFacts> facts,
            CandidateRule rule
    ) {
        List<Condition> conditions = evaluator.normalize(
                rule.allocationRule()
        );
        List<CandidateWorkerEntry> matches = new ArrayList<>();
        available.forEach((workerId, heldScore) -> {
            WorkerFacts worker = facts.get(workerId);
            if (worker != null && evaluator.matches(
                    conditions,
                    worker.workerId(),
                    worker.workerProperties(),
                    worker.platformProperties()
            )) {
                matches.add(new CandidateWorkerEntry(workerId, heldScore));
            }
        });
        return List.copyOf(matches);
    }

    private void failRuntime(Throwable failure) {
        synchronized (lifecycleGate) {
            if (state == State.RUNNING) {
                state = State.FAILED;
            }
        }
        LOGGER.log(
                System.Logger.Level.ERROR,
                "operation=workerMatching.run failure="
                        + failure.getClass().getSimpleName()
        );
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

    private static void logInvalidRule(String candidateId) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "operation=workerMatching.evaluate invalidRuleKind=candidate "
                        + "key=" + candidateId
        );
    }

    private static void logUnavailableRule(String candidateId) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "operation=workerMatching.evaluate "
                        + "unavailableRuleKind=candidate key=" + candidateId
        );
    }
}
