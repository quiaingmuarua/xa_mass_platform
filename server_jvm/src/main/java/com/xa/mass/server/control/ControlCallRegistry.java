package com.xa.mass.server.control;

import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ControlCallRegistry implements AutoCloseable {

    public static final String ADAPTER_TARGET_ADDRESS = "@adapter";
    public static final String FORWARD_PREFIX = "control-only:v1:";

    private final int maxCommandsPerAdapter;
    private final int maxPendingCalls;
    private final Map<String, LinkedHashMap<ControlTarget, CommandEntry>>
            commandsByAdapter = new LinkedHashMap<>();
    private final Map<String, TargetState> pendingTargetsByControlCallId =
            new LinkedHashMap<>();
    private final Map<String, BatchState> batchesByBatchId =
            new LinkedHashMap<>();
    private int pendingTargetCount;
    private boolean closed;

    public ControlCallRegistry(ControlCallProperties properties) {
        Objects.requireNonNull(properties, "properties");
        this.maxCommandsPerAdapter = properties.maxCommandsPerAdapter();
        this.maxPendingCalls = properties.maxPendingCalls();
    }

    public BatchHandle registerBatch(
            String controlBatchId,
            List<TargetPlan> targetPlans
    ) {
        requireNonBlank(controlBatchId, "controlBatchId");
        requirePlans(targetPlans);
        Map<BatchState, BatchOutcome> completions = new LinkedHashMap<>();
        BatchState batch;
        synchronized (this) {
            requireOpen();
            if (batchesByBatchId.containsKey(controlBatchId)) {
                throw new IllegalArgumentException(
                        "controlBatchId must be unique"
                );
            }
            requireCapacity(targetPlans);
            batch = new BatchState(controlBatchId);
            batchesByBatchId.put(controlBatchId, batch);

            for (TargetPlan plan : targetPlans) {
                TargetState target = new TargetState(batch, plan);
                batch.targets.put(plan.resultKey(), target);
                if (plan.initialOutcome() != null) {
                    target.outcome = plan.initialOutcome();
                    continue;
                }

                LinkedHashMap<ControlTarget, CommandEntry> mailbox =
                        commandsByAdapter.computeIfAbsent(
                                plan.adapterId(),
                                ignored -> new LinkedHashMap<>()
                        );
                CommandEntry previous = mailbox.remove(plan.target());
                if (previous != null) {
                    TargetState replaced = pendingTargetsByControlCallId
                            .remove(previous.controlCallId());
                    if (replaced != null && replaced.outcome == null) {
                        pendingTargetCount--;
                        replaced.outcome = TargetOutcome.unobserved(
                                TargetOutcomeReason.REPLACED
                        );
                        queueCompletionIfTerminalLocked(
                                replaced.batch,
                                completions
                        );
                    }
                }
                mailbox.put(
                        plan.target(),
                        new CommandEntry(
                                plan.controlCallId(),
                                plan.command()
                        )
                );
                pendingTargetsByControlCallId.put(
                        plan.controlCallId(),
                        target
                );
                pendingTargetCount++;
            }
            queueCompletionIfTerminalLocked(batch, completions);
        }
        completeOutsideLock(completions);
        return new BatchHandle(
                controlBatchId,
                batch.completion
        );
    }

    public Map<String, DeliveryCommand> consume(
            String adapterId,
            int limit,
            long nowMillis
    ) {
        Map<BatchState, BatchOutcome> completions = new LinkedHashMap<>();
        Map<String, DeliveryCommand> consumed = new LinkedHashMap<>();
        synchronized (this) {
            requireOpen();
            LinkedHashMap<ControlTarget, CommandEntry> mailbox =
                    commandsByAdapter.get(adapterId);
            if (mailbox != null) {
                Iterator<Map.Entry<ControlTarget, CommandEntry>> iterator =
                        mailbox.entrySet().iterator();
                while (iterator.hasNext() && consumed.size() < limit) {
                    Map.Entry<ControlTarget, CommandEntry> entry =
                            iterator.next();
                    iterator.remove();
                    TargetState target = pendingTargetsByControlCallId.get(
                            entry.getValue().controlCallId()
                    );
                    if (target == null || target.outcome != null) {
                        continue;
                    }
                    if (entry.getValue().command().executeBeforeMillis()
                            <= nowMillis) {
                        removePendingTargetLocked(target);
                        target.outcome = TargetOutcome.unobserved(
                                TargetOutcomeReason.TIMEOUT
                        );
                        queueCompletionIfTerminalLocked(
                                target.batch,
                                completions
                        );
                        continue;
                    }
                    target.consumed = true;
                    consumed.put(
                            entry.getKey().address(),
                            entry.getValue().command()
                    );
                }
                if (mailbox.isEmpty()) {
                    commandsByAdapter.remove(adapterId);
                }
            }
        }
        completeOutsideLock(completions);
        return Collections.unmodifiableMap(consumed);
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
                removePendingTargetLocked(target);
                target.outcome = TargetOutcome.observed(
                        report.outcomeCode(),
                        report.payload()
                );
                queueCompletionIfTerminalLocked(
                        target.batch,
                        completions
                );
                accepted++;
            }
        }
        completeOutsideLock(completions);
        return new CompletionCounts(accepted, rejected);
    }

    public void timeout(String controlBatchId) {
        completeRemaining(
                controlBatchId,
                TargetOutcomeReason.TIMEOUT
        );
    }

    public void cancel(String controlBatchId) {
        BatchState cancelled;
        synchronized (this) {
            cancelled = batchesByBatchId.remove(controlBatchId);
            if (cancelled == null || cancelled.finished) {
                return;
            }
            cancelled.finished = true;
            for (TargetState target : cancelled.targets.values()) {
                if (target.outcome == null) {
                    removePendingTargetLocked(target);
                    removeMailboxSlotLocked(target);
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
            for (BatchState batch : batchesByBatchId.values()) {
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
            batchesByBatchId.clear();
            pendingTargetsByControlCallId.clear();
            commandsByAdapter.clear();
            pendingTargetCount = 0;
        }
        completions.forEach(completion -> completion.batch.completion.complete(
                completion.outcome
        ));
    }

    private void completeRemaining(
            String controlBatchId,
            TargetOutcomeReason reason
    ) {
        BatchCompletion completion;
        synchronized (this) {
            BatchState batch = batchesByBatchId.remove(controlBatchId);
            if (batch == null || batch.finished) {
                return;
            }
            batch.finished = true;
            for (TargetState target : batch.targets.values()) {
                if (target.outcome == null) {
                    removePendingTargetLocked(target);
                    removeMailboxSlotLocked(target);
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
        String controlCallId = report.forward().substring(
                FORWARD_PREFIX.length()
        );
        TargetState target = pendingTargetsByControlCallId.get(controlCallId);
        if (target == null
                || target.outcome != null
                || !target.consumed
                || !target.adapterId.equals(adapterId)
                || !target.messageType.equals(report.messageType())) {
            return null;
        }
        WorkerDeliveryProtocol.DeliveryReportOutcomeClass outcome =
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode(
                        report.outcomeCode()
                );
        if (target.target.type() == ControlTargetType.WORKER) {
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
        int replacements = 0;
        int eligible = 0;
        Map<String, Integer> newSlotsByAdapter = new LinkedHashMap<>();
        for (TargetPlan plan : plans) {
            if (plan.initialOutcome() != null) {
                continue;
            }
            eligible++;
            LinkedHashMap<ControlTarget, CommandEntry> mailbox =
                    commandsByAdapter.get(plan.adapterId());
            CommandEntry previous = mailbox == null
                    ? null
                    : mailbox.get(plan.target());
            if (previous != null
                    && pendingTargetsByControlCallId.containsKey(
                            previous.controlCallId()
                    )) {
                replacements++;
            } else {
                newSlotsByAdapter.merge(
                        plan.adapterId(),
                        1,
                        Integer::sum
                );
            }
        }
        if (pendingTargetCount - replacements + eligible
                > maxPendingCalls) {
            throw capacityExceeded(
                    "Control Call waiter capacity is exhausted"
            );
        }
        for (Map.Entry<String, Integer> addition
                : newSlotsByAdapter.entrySet()) {
            LinkedHashMap<ControlTarget, CommandEntry> mailbox =
                    commandsByAdapter.get(addition.getKey());
            int currentSize = mailbox == null ? 0 : mailbox.size();
            if (currentSize + addition.getValue()
                    > maxCommandsPerAdapter) {
                throw capacityExceeded(
                        "Control Command capacity is exhausted for Adapter"
                );
            }
        }
    }

    private void removePendingTargetLocked(TargetState target) {
        if (target.controlCallId != null
                && pendingTargetsByControlCallId.remove(
                        target.controlCallId,
                        target
                )) {
            pendingTargetCount--;
        }
    }

    private void removeMailboxSlotLocked(TargetState target) {
        if (target.target == null || target.adapterId == null) {
            return;
        }
        LinkedHashMap<ControlTarget, CommandEntry> mailbox =
                commandsByAdapter.get(target.adapterId);
        if (mailbox == null) {
            return;
        }
        CommandEntry entry = mailbox.get(target.target);
        if (entry != null
                && entry.controlCallId().equals(target.controlCallId)) {
            mailbox.remove(target.target);
        }
        if (mailbox.isEmpty()) {
            commandsByAdapter.remove(target.adapterId);
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
        batchesByBatchId.remove(batch.controlBatchId, batch);
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
                    ServerErrorCode.CONTROL_CALL_UNAVAILABLE,
                    "controlCall.register",
                    "Control Call registry is stopping",
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
        Set<String> targetAddresses = new LinkedHashSet<>();
        for (TargetPlan plan : plans) {
            Objects.requireNonNull(plan, "targetPlan");
            if (!resultKeys.add(plan.resultKey())) {
                throw new IllegalArgumentException(
                        "target result keys must be unique"
                );
            }
            if (plan.initialOutcome() == null) {
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

    private static ServerException capacityExceeded(String message) {
        return new ServerException(
                ServerErrorCode.CONTROL_CALL_CAPACITY_EXCEEDED,
                "controlCall.registerBatch",
                message,
                null
        );
    }

    public enum ControlTargetType {
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
        REPLACED,
        SHUTDOWN,
        NOT_FOUND,
        CONTROL_ONLY_REQUIRED,
        SCORE_UNAVAILABLE,
        NOT_BOUND,
        ENDPOINT_UNAVAILABLE,
        POLLING_ENDPOINT
    }

    public record ControlTarget(
            ControlTargetType type,
            String targetId
    ) {
        public ControlTarget {
            Objects.requireNonNull(type, "type");
            requireNonBlank(targetId, "targetId");
        }

        public static ControlTarget worker(String workerId) {
            return new ControlTarget(ControlTargetType.WORKER, workerId);
        }

        public static ControlTarget adapter(String adapterId) {
            return new ControlTarget(ControlTargetType.ADAPTER, adapterId);
        }

        String address() {
            return type == ControlTargetType.ADAPTER
                    ? ADAPTER_TARGET_ADDRESS
                    : targetId;
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
            String controlCallId,
            String adapterId,
            ControlTarget target,
            DeliveryCommand command,
            TargetOutcome initialOutcome
    ) {
        public TargetPlan {
            requireNonBlank(resultKey, "resultKey");
            boolean commandPlan = initialOutcome == null;
            if (commandPlan) {
                requireNonBlank(controlCallId, "controlCallId");
                requireNonBlank(adapterId, "adapterId");
                Objects.requireNonNull(target, "target");
                Objects.requireNonNull(command, "command");
            } else if (controlCallId != null
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
                String controlCallId,
                String adapterId,
                ControlTarget target,
                DeliveryCommand command
        ) {
            return new TargetPlan(
                    resultKey,
                    controlCallId,
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
            String controlBatchId,
            CompletionStage<BatchOutcome> completion
    ) {
    }

    public record BatchOutcome(
            String controlBatchId,
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
            String controlCallId,
            DeliveryCommand command
    ) {
    }

    private record BatchCompletion(
            BatchState batch,
            BatchOutcome outcome
    ) {
    }

    private static final class BatchState {

        private final String controlBatchId;
        private final LinkedHashMap<String, TargetState> targets =
                new LinkedHashMap<>();
        private final CompletableFuture<BatchOutcome> completion =
                new CompletableFuture<>();
        private boolean finished;

        private BatchState(String controlBatchId) {
            this.controlBatchId = controlBatchId;
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
            return new BatchOutcome(controlBatchId, results);
        }
    }

    private static final class TargetState {

        private final BatchState batch;
        private final String controlCallId;
        private final String adapterId;
        private final ControlTarget target;
        private final String messageType;
        private boolean consumed;
        private TargetOutcome outcome;

        private TargetState(BatchState batch, TargetPlan plan) {
            this.batch = batch;
            this.controlCallId = plan.controlCallId();
            this.adapterId = plan.adapterId();
            this.target = plan.target();
            this.messageType = plan.command() == null
                    ? null
                    : plan.command().messageType();
            this.outcome = plan.initialOutcome();
        }
    }
}
