package com.xa.mass.engine.stage;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owner for task item stage evidence.
 *
 * <p>This owner stores bounded stage evidence only. It deliberately does not
 * write public result rows, final result convergence, task terminal state, or
 * assignment/runtime work queues.</p>
 */
public final class TaskStageEvidenceOwner {

    private static final int DEFAULT_RECENT_HISTORY_LIMIT = 8;

    private final Clock clock;
    private final int recentHistoryLimit;
    private final ConcurrentHashMap<StageKey, TaskStageProjection> projectionsByStage = new ConcurrentHashMap<>();

    public TaskStageEvidenceOwner() {
        this(Clock.systemUTC(), DEFAULT_RECENT_HISTORY_LIMIT);
    }

    TaskStageEvidenceOwner(Clock clock, int recentHistoryLimit) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (recentHistoryLimit <= 0) {
            throw new IllegalArgumentException("recentHistoryLimit must be > 0");
        }
        this.recentHistoryLimit = recentHistoryLimit;
    }

    public TaskStageEvidenceResult applyEvidence(TaskStageEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("evidence must not be null");
        }
        StageKey key = new StageKey(evidence.taskId(), evidence.messageId(), evidence.stageName());
        Instant now = Instant.now(clock);
        TaskStageEvidenceResult[] result = new TaskStageEvidenceResult[1];
        projectionsByStage.compute(key, (ignored, current) -> {
            if (current == null) {
                TaskStageProjection created = projectionFrom(evidence, now, List.of(evidence));
                result[0] = accepted(evidence, created, "task stage evidence accepted");
                return created;
            }
            if (evidence.stageVersion() < current.stageVersion()) {
                result[0] = new TaskStageEvidenceResult(
                        TaskStageEvidenceStatus.STALE,
                        evidence.taskId(),
                        evidence.messageId(),
                        evidence.stageName(),
                        evidence.stageVersion(),
                        false,
                        current,
                        "task stage evidence version is stale"
                );
                return current;
            }
            if (evidence.stageVersion() == current.stageVersion()) {
                TaskStageEvidence latest = current.recentEvidence().isEmpty() ? null : current.recentEvidence().get(0);
                if (evidence.equals(latest)) {
                    result[0] = new TaskStageEvidenceResult(
                            TaskStageEvidenceStatus.IDEMPOTENT,
                            evidence.taskId(),
                            evidence.messageId(),
                            evidence.stageName(),
                            evidence.stageVersion(),
                            false,
                            current,
                            "task stage evidence already applied"
                    );
                } else {
                    result[0] = new TaskStageEvidenceResult(
                            TaskStageEvidenceStatus.CONFLICT,
                            evidence.taskId(),
                            evidence.messageId(),
                            evidence.stageName(),
                            evidence.stageVersion(),
                            false,
                            current,
                            "task stage evidence version conflicts with existing payload"
                    );
                }
                return current;
            }

            TaskStageProjection updated = projectionFrom(evidence, now,
                    boundedHistory(evidence, current.recentEvidence()));
            result[0] = accepted(evidence, updated, "task stage evidence accepted");
            return updated;
        });
        return result[0];
    }

    public Optional<TaskStageProjection> projection(String taskId, String messageId, String stageName) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedMessageId = normalizeNullable(messageId);
        String normalizedStageName = normalizeNullable(stageName);
        if (normalizedTaskId == null || normalizedMessageId == null || normalizedStageName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(projectionsByStage.get(new StageKey(
                normalizedTaskId,
                normalizedMessageId,
                normalizedStageName
        )));
    }

    public List<TaskStageProjection> projectionsForMessage(String taskId, String messageId) {
        String normalizedTaskId = normalizeNullable(taskId);
        String normalizedMessageId = normalizeNullable(messageId);
        if (normalizedTaskId == null || normalizedMessageId == null) {
            return List.of();
        }
        return projectionsByStage.values().stream()
                .filter(projection -> normalizedTaskId.equals(projection.taskId())
                        && normalizedMessageId.equals(projection.messageId()))
                .sorted(java.util.Comparator.comparing(TaskStageProjection::stageName))
                .toList();
    }

    private TaskStageEvidenceResult accepted(TaskStageEvidence evidence,
                                             TaskStageProjection projection,
                                             String reason) {
        return new TaskStageEvidenceResult(
                TaskStageEvidenceStatus.ACCEPTED,
                evidence.taskId(),
                evidence.messageId(),
                evidence.stageName(),
                evidence.stageVersion(),
                true,
                projection,
                reason
        );
    }

    private static TaskStageProjection projectionFrom(TaskStageEvidence evidence,
                                                      Instant acceptedAt,
                                                      List<TaskStageEvidence> recentEvidence) {
        return new TaskStageProjection(
                evidence.taskId(),
                evidence.messageId(),
                evidence.stageName(),
                evidence.stageVersion(),
                evidence.stageStatus(),
                evidence.detail(),
                evidence.observedAt(),
                acceptedAt,
                recentEvidence
        );
    }

    private List<TaskStageEvidence> boundedHistory(TaskStageEvidence evidence, List<TaskStageEvidence> previous) {
        Deque<TaskStageEvidence> values = new ArrayDeque<>();
        values.add(evidence);
        if (previous != null) {
            for (TaskStageEvidence item : previous) {
                if (values.size() >= recentHistoryLimit) {
                    break;
                }
                values.addLast(item);
            }
        }
        return List.copyOf(new ArrayList<>(values));
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record StageKey(String taskId, String messageId, String stageName) {
    }
}
