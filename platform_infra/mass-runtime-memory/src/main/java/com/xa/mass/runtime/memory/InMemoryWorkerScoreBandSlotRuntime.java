package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandAcquireRequest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlot;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotMetadata;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionCommand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionResult;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionRules;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory score-band worker slot state machine.
 */
public class InMemoryWorkerScoreBandSlotRuntime implements WorkerScoreBandSlotRuntime {

    private final Map<String, Map<String, WorkerScoreBandSlot>> slotsByHomeBucket = new LinkedHashMap<>();

    @Override
    public synchronized void upsert(WorkerScoreBandSlotMetadata metadata,
                                    long initialScore,
                                    String reasonCode,
                                    long observedAtMillis) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        slotsByHomeBucket
                .computeIfAbsent(metadata.homeBucketId(), ignored -> new LinkedHashMap<>())
                .put(metadata.workerId(), new WorkerScoreBandSlot(metadata, initialScore));
    }

    @Override
    public synchronized Optional<WorkerScoreBandSlot> slot(String homeBucketId, String workerId) {
        Map<String, WorkerScoreBandSlot> slots = slotsByHomeBucket.get(normalize(homeBucketId));
        if (slots == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(slots.get(normalize(workerId)));
    }

    @Override
    public synchronized List<WorkerScoreBandSlot> acquire(WorkerScoreBandAcquireRequest request) {
        if (request == null || request.maxCount() <= 0 || request.homeBucketIds().isEmpty()) {
            return List.of();
        }
        if (request.targetWorkerId() != null) {
            return acquireTarget(request);
        }
        ArrayList<WorkerScoreBandSlot> candidates = new ArrayList<>();
        for (String homeBucketId : request.homeBucketIds()) {
            Map<String, WorkerScoreBandSlot> slots = slotsByHomeBucket.get(homeBucketId);
            if (slots == null) {
                continue;
            }
            for (WorkerScoreBandSlot slot : slots.values()) {
                if (WorkerScoreBand.isAcquireVisible(slot.score(), request.nowMillis())) {
                    candidates.add(slot);
                }
            }
        }
        candidates.sort(Comparator
                .comparingLong(WorkerScoreBandSlot::score)
                .thenComparing(WorkerScoreBandSlot::workerId));
        if (candidates.size() <= request.maxCount()) {
            return List.copyOf(candidates);
        }
        return List.copyOf(candidates.subList(0, request.maxCount()));
    }

    @Override
    public synchronized WorkerScoreBandTransitionResult transition(WorkerScoreBandTransitionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        Optional<WorkerScoreBandSlot> current = slot(command.homeBucketId(), command.workerId());
        if (current.isEmpty()) {
            return WorkerScoreBandTransitionResult.rejected(
                    WorkerScoreBandTransitionStatus.MISSING_SLOT,
                    null,
                    "slot not found"
            );
        }
        WorkerScoreBandSlot before = current.get();
        WorkerScoreBandTransitionStatus status =
                WorkerScoreBandTransitionRules.validate(before.score(), command);
        if (status != WorkerScoreBandTransitionStatus.ACCEPTED) {
            return WorkerScoreBandTransitionResult.rejected(status, before, status.name());
        }
        WorkerScoreBandSlot after = before;
        if (WorkerScoreBandTransitionRules.writesScore(command)) {
            after = new WorkerScoreBandSlot(before.metadata(), command.targetScore());
            slotsByHomeBucket.get(before.homeBucketId()).put(before.workerId(), after);
        }
        return WorkerScoreBandTransitionResult.accepted(before, after);
    }

    @Override
    public synchronized void remove(String homeBucketId, String workerId, String reasonCode, long observedAtMillis) {
        Map<String, WorkerScoreBandSlot> slots = slotsByHomeBucket.get(normalize(homeBucketId));
        if (slots == null) {
            return;
        }
        slots.remove(normalize(workerId));
        if (slots.isEmpty()) {
            slotsByHomeBucket.remove(normalize(homeBucketId));
        }
    }

    private List<WorkerScoreBandSlot> acquireTarget(WorkerScoreBandAcquireRequest request) {
        for (String homeBucketId : request.homeBucketIds()) {
            Optional<WorkerScoreBandSlot> candidate = slot(homeBucketId, request.targetWorkerId());
            if (candidate.isPresent()
                    && WorkerScoreBand.isAcquireVisible(candidate.get().score(), request.nowMillis())) {
                return List.of(candidate.get());
            }
        }
        return List.of();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
