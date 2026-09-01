package com.xa.mass.server.delivery.directcall;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DirectCallRegistry implements AutoCloseable {

    public static final String FORWARD_PREFIX = "direct-call:v1:";

    private final int maxAdapterCommandsPerAdapter;
    private final int maxPendingCalls;
    private final Map<String, ArrayDeque<CommandEntry>>
            adapterCommandsByAdapter = new LinkedHashMap<>();
    private final Map<String, TargetState> pendingTargetsByCorrelationId =
            new LinkedHashMap<>();
    private final Map<String, BatchState> batchesByDirectCallId =
            new LinkedHashMap<>();
    private int pendingTargetCount;
    private boolean closed;

    public DirectCallRegistry(DirectCallProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.maxAdapterCommandsPerAdapter =
                properties.maxAdapterCommandsPerAdapter();
        this.maxPendingCalls = properties.maxPendingCalls();
    }

    public BatchHandle registerBatch(
            String directCallId,
            List<TargetPlan> targetPlans
    ) {
        requireNonBlank(directCallId, "directCallId");
        requirePlans(targetPlans);
        Map<BatchState, BatchOutcome> completions = new LinkedHashMap<>();
        BatchState batch;
        synchronized (this) {
            requireOpen();
            if (batchesByDirectCallId.containsKey(directCallId)) {
                throw new IllegalArgumentException(
                        "directCallId must be unique"
                );
            }
            requireCapacity(targetPlans);
            batch = new BatchState(directCallId);
            batchesByDirectCallId.put(directCallId, batch);

            for (TargetPlan plan : targetPlans) {
                TargetState target = new TargetState(batch, plan);
                batch.targets.put(plan.resultKey(), target);
                if (plan.initialOutcome() != null) {
                    continue;
                }
                if (pendingTargetsByCorrelationId.put(
                        plan.correlationId(),
                        target
                ) != null) {
                    throw new IllegalArgumentException(
                            "correlationId must be unique"
                    );
                }
                pendingTargetCount++;
                if (plan.target().type() == DirectTargetType.ADAPTER) {
                    adapterCommandsByAdapter.computeIfAbsent(
                            plan.adapterId(),
                            ignored -> new ArrayDeque<>()
                    ).addLast(new CommandEntry(
                            plan.correlationId(),
                            plan.command()
                    ));
                }
            }
            queueCompletionIfTerminalLocked(batch, completions);
        }
        completeOutsideLock(completions);
        return new BatchHandle(directCallId, batch.completion);
    }

    public List<DeliveryCommand> consumeAdapterCommands(
            String adapterId,
            int limit,
            long nowMillis
    ) {
        Map<BatchState, BatchOutcome> completions = new LinkedHashMap<>();
        List<DeliveryCommand> consumed = new ArrayList<>();
        synchronized (this) {
            requireOpen();
            requirePositiveLimit(limit);
            ArrayDeque<CommandEntry> mailbox =
                    adapterCommandsByAdapter.get(adapterId);
            if (mailbox != null) {
                while (!mailbox.isEmpty() && consumed.size() < limit) {
                    CommandEntry entry = mailbox.removeFirst();
                    TargetState target = pendingTargetsByCorrelationId.get(
                            entry.correlationId()
                    );
                    if (target == null || target.outcome != null) {
                        continue;
                    }
                    if (entry.command().executeBeforeMillis() <= nowMillis) {
                        completeTargetLocked(
                                target,
                                TargetOutcome.unobserved(
                                        TargetOutcomeReason.TIMEOUT
                                ),
                                completions
                        );
                        continue;
                    }
                    target.resultEligible = true;
                    consumed.add(entry.command());
                }
                if (mailbox.isEmpty()) {
                    adapterCommandsByAdapter.remove(adapterId);
                }
            }
        }
        completeOutsideLock(completions);
        return List.copyOf(consumed);
    }

    public int completeTargets(
            Map<String, TargetOutcome> outcomesByCorrelationId
    ) {
        Objects.requireNonNull(
                outcomesByCorrelationId,
                "outcomesByCorrelationId"
        );
        Map<BatchState, BatchOutcome> completions = new LinkedHashMap<>();
        int completed = 0;
        synchronized (this) {
            for (Map.Entry<String, TargetOutcome> entry
                    : outcomesByCorrelationId.entrySet()) {
                TargetState target = pendingTargetsByCorrelationId.get(
                        entry.getKey()
                );
                if (target == null || target.outcome != null) {
                    continue;
                }
                completeTargetLocked(
                        target,
                        Objects.requireNonNull(entry.getValue(), "outcome"),
                        completions
                );
                completed++;
            }
        }
        completeOutsideLock(completions);
        return completed;
    }

    public CompletionCounts completeReports(
            String adapterId,
            List<DeliveryReport> reports
    ) {
        Objects.requireNonNull(reports, "reports");
        Map<BatchState, BatchOutcome> completions = new LinkedHashMap<>();
        int accepted = 0;
        int rejected = 0;
        synchronized (this) {
            for (DeliveryReport report : reports) {
                TargetState target = matchingTarget(adapterId, report);
                if (target == null) {
                    rejected++;
                    continue;
                }
                completeTargetLocked(
                        target,
                        TargetOutcome.observed(
                                report.outcomeCode(),
                                report.payload()
                        ),
                        completions
                );
                accepted++;
            }
        }
        completeOutsideLock(completions);
        return new CompletionCounts(accepted, rejected);
    }

    public void timeout(String directCallId) {
        completeRemaining(directCallId, TargetOutcomeReason.TIMEOUT);
    }

    public void cancel(String directCallId) {
        BatchState cancelled;
        synchronized (this) {
            cancelled = batchesByDirectCallId.remove(directCallId);
            if (cancelled == null || cancelled.finished) {
                return;
            }
            cancelled.finished = true;
            for (TargetState target : cancelled.targets.values()) {
                if (target.outcome == null) {
                    removePendingTargetLocked(target);
                    removeAdapterMailboxEntryLocked(target);
                }
            }
        }
        cancelled.completion.cancel(false);
    }

    @Override
    public void close() {
        List<BatchCompletion> completions = new ArrayList<>();
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            for (BatchState batch : batchesByDirectCallId.values()) {
                if (batch.finished) {
                    continue;
                }
                for (TargetState target : batch.targets.values()) {
                    if (target.outcome == null) {
                        target.outcome = TargetOutcome.unobserved(
                                TargetOutcomeReason.SHUTDOWN
                        );
                    }
                }
                batch.finished = true;
                completions.add(new BatchCompletion(
                        batch,
                        batch.outcome()
                ));
            }
            batchesByDirectCallId.clear();
            pendingTargetsByCorrelationId.clear();
            adapterCommandsByAdapter.clear();
            pendingTargetCount = 0;
        }
        completions.forEach(completion -> completion.batch.completion.complete(
                completion.outcome
        ));
    }

    private void completeRemaining(
            String directCallId,
            TargetOutcomeReason reason
    ) {
        BatchCompletion completion;
        synchronized (this) {
            BatchState batch = batchesByDirectCallId.remove(directCallId);
            if (batch == null || batch.finished) {
                return;
            }
            batch.finished = true;
            for (TargetState target : batch.targets.values()) {
                if (target.outcome == null) {
                    removePendingTargetLocked(target);
                    removeAdapterMailboxEntryLocked(target);
                    target.outcome = TargetOutcome.unobserved(reason);
                }
            }
            completion = new BatchCompletion(batch, batch.outcome());
        }
        completion.batch.completion.complete(completion.outcome);
    }

    private TargetState matchingTarget(
            String adapterId,
            DeliveryReport report
    ) {
        if (closed
                || report == null
                || report.dst() != DeliveryEndpoint.SYSTEM
                || report.forward() == null
                || !report.forward().startsWith(FORWARD_PREFIX)) {
            return null;
        }
        String correlationId = report.forward().substring(
                FORWARD_PREFIX.length()
        );
        TargetState target = pendingTargetsByCorrelationId.get(correlationId);
        if (target == null
                || target.outcome != null
                || !target.resultEligible
                || !target.adapterId.equals(adapterId)
                || !target.messageType.equals(report.messageType())) {
            return null;
        }
        WorkerDeliveryProtocol.DeliveryReportOutcomeClass outcome =
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode(
                        report.outcomeCode()
                );
        if (target.target.type() == DirectTargetType.WORKER) {
            return report.src() == DeliveryEndpoint.WORKER
                    && target.target.targetId().equals(report.sourceId())
                    && (outcome == WorkerDeliveryProtocol
                            .DeliveryReportOutcomeClass.SUCCESS
                    || outcome == WorkerDeliveryProtocol
                            .DeliveryReportOutcomeClass.WORKER_FAILURE)
                    ? target
                    : null;
        }
        return report.src() == DeliveryEndpoint.ADAPTER
                && target.adapterId.equals(report.sourceId())
                && report.outcomeCode().startsWith("2")
                ? target
                : null;
    }

    private void requireCapacity(List<TargetPlan> plans) {
        int pendingAdditions = 0;
        Map<String, Integer> adapterAdditions = new LinkedHashMap<>();
        for (TargetPlan plan : plans) {
            if (plan.initialOutcome() != null) {
                continue;
            }
            pendingAdditions++;
            if (plan.target().type() == DirectTargetType.ADAPTER) {
                adapterAdditions.merge(
                        plan.adapterId(),
                        1,
                        Integer::sum
                );
            }
        }
        if (pendingTargetCount + pendingAdditions > maxPendingCalls) {
            throw capacityExceeded(
                    "Direct Call waiter capacity is exhausted"
            );
        }
        for (Map.Entry<String, Integer> addition
                : adapterAdditions.entrySet()) {
            ArrayDeque<CommandEntry> mailbox =
                    adapterCommandsByAdapter.get(addition.getKey());
            int currentSize = mailbox == null ? 0 : mailbox.size();
            if (currentSize + addition.getValue()
                    > maxAdapterCommandsPerAdapter) {
                throw capacityExceeded(
                        "Adapter Direct Command capacity is exhausted"
                );
            }
        }
    }

    private void completeTargetLocked(
            TargetState target,
            TargetOutcome outcome,
            Map<BatchState, BatchOutcome> completions
    ) {
        removePendingTargetLocked(target);
        removeAdapterMailboxEntryLocked(target);
        target.outcome = outcome;
        queueCompletionIfTerminalLocked(target.batch, completions);
    }

    private void removePendingTargetLocked(TargetState target) {
        if (target.correlationId != null
                && pendingTargetsByCorrelationId.remove(
                        target.correlationId,
                        target
                )) {
            pendingTargetCount--;
        }
    }

    private void removeAdapterMailboxEntryLocked(TargetState target) {
        if (target.target == null
                || target.target.type() != DirectTargetType.ADAPTER) {
            return;
        }
        ArrayDeque<CommandEntry> mailbox =
                adapterCommandsByAdapter.get(target.adapterId);
        if (mailbox == null) {
            return;
        }
        mailbox.removeIf(entry -> entry.correlationId().equals(
                target.correlationId
        ));
        if (mailbox.isEmpty()) {
            adapterCommandsByAdapter.remove(target.adapterId);
        }
    }

    private void queueCompletionIfTerminalLocked(
            BatchState batch,
            Map<BatchState, BatchOutcome> completions
    ) {
        if (batch.finished || !batch.isTerminal()) {
            return;
        }
        batch.finished = true;
        batchesByDirectCallId.remove(batch.directCallId, batch);
        completions.put(batch, batch.outcome());
    }

    private static void completeOutsideLock(
            Map<BatchState, BatchOutcome> completions
    ) {
        completions.forEach((batch, outcome) ->
                batch.completion.complete(outcome));
    }

    private void requireOpen() {
        if (closed) {
            throw new ServerException(
                    ServerErrorCode.DIRECT_CALL_UNAVAILABLE,
                    "directCall.register",
                    "Direct Call registry is stopping",
                    null
            );
        }
    }

    private static void requirePlans(List<TargetPlan> plans) {
        if (plans == null || plans.isEmpty() || plans.size() > 100) {
            throw new IllegalArgumentException(
                    "targetPlans must contain between 1 and 100 entries"
            );
        }
        Set<String> resultKeys = new LinkedHashSet<>();
        Set<String> correlations = new LinkedHashSet<>();
        Set<String> targetAddresses = new LinkedHashSet<>();
        for (TargetPlan plan : plans) {
            Objects.requireNonNull(plan, "targetPlan");
            if (!resultKeys.add(plan.resultKey())) {
                throw new IllegalArgumentException(
                        "target result keys must be unique"
                );
            }
            if (plan.initialOutcome() == null) {
                if (!correlations.add(plan.correlationId())) {
                    throw new IllegalArgumentException(
                            "target correlation ids must be unique"
                    );
                }
                String address = plan.adapterId()
                        + "\u0000"
                        + plan.target().type()
                        + "\u0000"
                        + plan.target().targetId();
                if (!targetAddresses.add(address)) {
                    throw new IllegalArgumentException(
                            "target addresses must be unique"
                    );
                }
            }
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private static void requirePositiveLimit(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }

    private static ServerException capacityExceeded(String message) {
        return new ServerException(
                ServerErrorCode.DIRECT_CALL_CAPACITY_EXCEEDED,
                "directCall.registerBatch",
                message,
                null
        );
    }

    public enum DirectTargetType {
        WORKER,
        ADAPTER
    }

    public enum TargetOutcomeStatus {
        OBSERVED,
        UNOBSERVED,
        REJECTED
    }

    public enum TargetOutcomeReason {
        TIMEOUT,
        SHUTDOWN,
        NOT_FOUND,
        NOT_BOUND,
        ENDPOINT_MISMATCH,
        COMMAND_SLOT_OCCUPIED,
        SUBMISSION_UNKNOWN
    }

    public record DirectTarget(
            DirectTargetType type,
            String targetId
    ) {
        public DirectTarget {
            Objects.requireNonNull(type, "type");
            requireNonBlank(targetId, "targetId");
        }

        public static DirectTarget worker(String workerId) {
            return new DirectTarget(DirectTargetType.WORKER, workerId);
        }

        public static DirectTarget adapter(String adapterId) {
            return new DirectTarget(DirectTargetType.ADAPTER, adapterId);
        }
    }

    public record TargetOutcome(
            TargetOutcomeStatus status,
            String outcomeCode,
            String payload,
            TargetOutcomeReason reason
    ) {
        public static TargetOutcome observed(
                String outcomeCode,
                String payload
        ) {
            return new TargetOutcome(
                    TargetOutcomeStatus.OBSERVED,
                    outcomeCode,
                    payload,
                    null
            );
        }

        public static TargetOutcome unobserved(TargetOutcomeReason reason) {
            return new TargetOutcome(
                    TargetOutcomeStatus.UNOBSERVED,
                    null,
                    null,
                    reason
            );
        }

        public static TargetOutcome rejected(TargetOutcomeReason reason) {
            return new TargetOutcome(
                    TargetOutcomeStatus.REJECTED,
                    null,
                    null,
                    reason
            );
        }
    }

    public record TargetPlan(
            String resultKey,
            String correlationId,
            String adapterId,
            DirectTarget target,
            DeliveryCommand command,
            TargetOutcome initialOutcome
    ) {
        public TargetPlan {
            requireNonBlank(resultKey, "resultKey");
            boolean commandPlan = initialOutcome == null;
            if (commandPlan) {
                requireNonBlank(correlationId, "correlationId");
                requireNonBlank(adapterId, "adapterId");
                Objects.requireNonNull(target, "target");
                Objects.requireNonNull(command, "command");
            } else if (correlationId != null
                    || adapterId != null
                    || target != null
                    || command != null
                    || initialOutcome.status()
                    != TargetOutcomeStatus.REJECTED) {
                throw new IllegalArgumentException(
                        "Rejected TargetPlan must contain only its outcome"
                );
            }
        }

        public static TargetPlan command(
                String resultKey,
                String correlationId,
                String adapterId,
                DirectTarget target,
                DeliveryCommand command
        ) {
            return new TargetPlan(
                    resultKey,
                    correlationId,
                    adapterId,
                    target,
                    command,
                    null
            );
        }

        public static TargetPlan rejected(
                String resultKey,
                TargetOutcomeReason reason
        ) {
            return new TargetPlan(
                    resultKey,
                    null,
                    null,
                    null,
                    null,
                    TargetOutcome.rejected(reason)
            );
        }
    }

    public record BatchHandle(
            String directCallId,
            CompletionStage<BatchOutcome> completion
    ) {
    }

    public record BatchOutcome(
            String directCallId,
            Map<String, TargetOutcome> results
    ) {
        public BatchOutcome {
            results = Collections.unmodifiableMap(
                    new LinkedHashMap<>(results)
            );
        }
    }

    public record CompletionCounts(
            int acceptedCount,
            int rejectedCount
    ) {
    }

    private record CommandEntry(
            String correlationId,
            DeliveryCommand command
    ) {
    }

    private record BatchCompletion(
            BatchState batch,
            BatchOutcome outcome
    ) {
    }

    private static final class BatchState {

        private final String directCallId;
        private final LinkedHashMap<String, TargetState> targets =
                new LinkedHashMap<>();
        private final CompletableFuture<BatchOutcome> completion =
                new CompletableFuture<>();
        private boolean finished;

        private BatchState(String directCallId) {
            this.directCallId = directCallId;
        }

        private boolean isTerminal() {
            return targets.values().stream().allMatch(
                    target -> target.outcome != null
            );
        }

        private BatchOutcome outcome() {
            Map<String, TargetOutcome> results = new LinkedHashMap<>();
            targets.forEach((key, target) -> results.put(
                    key,
                    Objects.requireNonNull(target.outcome, "target outcome")
            ));
            return new BatchOutcome(directCallId, results);
        }
    }

    private static final class TargetState {

        private final BatchState batch;
        private final String correlationId;
        private final String adapterId;
        private final DirectTarget target;
        private final String messageType;
        private boolean resultEligible;
        private TargetOutcome outcome;

        private TargetState(BatchState batch, TargetPlan plan) {
            this.batch = batch;
            this.correlationId = plan.correlationId();
            this.adapterId = plan.adapterId();
            this.target = plan.target();
            this.messageType = plan.command() == null
                    ? null
                    : plan.command().messageType();
            this.resultEligible = plan.target() != null
                    && plan.target().type() == DirectTargetType.WORKER;
            this.outcome = plan.initialOutcome();
        }
    }
}
