package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.WorkerStateProjection;
import com.xa.mass.runtime.worker.WorkerStateProjectionResult;
import com.xa.mass.runtime.worker.WorkerStateProjectionStatus;
import com.xa.mass.runtime.worker.WorkerStateReport;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded owner projection for worker-originated state reports.
 *
 * <p>This owner does not write reachability, load, matching, or task-result
 * truth. Raw reports stay in a bounded per-worker history, and callers may read
 * only the latest projection or recent diagnostic evidence.</p>
 */
public final class WorkerStateProjectionOwner {

    private static final int DEFAULT_RECENT_HISTORY_LIMIT = 8;

    private final Clock clock;
    private final int recentHistoryLimit;
    private final ConcurrentHashMap<String, WorkerStateProjection> projectionsByWorkerId = new ConcurrentHashMap<>();

    public WorkerStateProjectionOwner() {
        this(Clock.systemUTC(), DEFAULT_RECENT_HISTORY_LIMIT);
    }

    WorkerStateProjectionOwner(Clock clock, int recentHistoryLimit) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        if (recentHistoryLimit <= 0) {
            throw new IllegalArgumentException("recentHistoryLimit must be > 0");
        }
        this.recentHistoryLimit = recentHistoryLimit;
    }

    public WorkerStateProjectionResult applyReport(WorkerStateReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }
        Instant now = Instant.now(clock);
        WorkerStateProjectionResult[] result = new WorkerStateProjectionResult[1];
        projectionsByWorkerId.compute(report.workerId(), (workerId, current) -> {
            if (current == null) {
                WorkerStateProjection created = projectionFrom(report, now, List.of(report));
                result[0] = accepted(report, created, "worker state report accepted");
                return created;
            }
            if (report.stateVersion() < current.stateVersion()) {
                result[0] = new WorkerStateProjectionResult(
                        WorkerStateProjectionStatus.STALE,
                        report.workerId(),
                        report.stateVersion(),
                        false,
                        current,
                        "worker state report version is stale"
                );
                return current;
            }
            if (report.stateVersion() == current.stateVersion()) {
                WorkerStateReport latest = current.recentReports().isEmpty() ? null : current.recentReports().get(0);
                if (report.equals(latest)) {
                    result[0] = new WorkerStateProjectionResult(
                            WorkerStateProjectionStatus.IDEMPOTENT,
                            report.workerId(),
                            report.stateVersion(),
                            false,
                            current,
                            "worker state report already applied"
                    );
                } else {
                    result[0] = new WorkerStateProjectionResult(
                            WorkerStateProjectionStatus.CONFLICT,
                            report.workerId(),
                            report.stateVersion(),
                            false,
                            current,
                            "worker state report version conflicts with existing payload"
                    );
                }
                return current;
            }

            List<WorkerStateReport> history = boundedHistory(report, current.recentReports());
            WorkerStateProjection updated = projectionFrom(report, now, history);
            result[0] = accepted(report, updated, "worker state report accepted");
            return updated;
        });
        return result[0];
    }

    public Optional<WorkerStateProjection> projection(String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        return normalizedWorkerId == null
                ? Optional.empty()
                : Optional.ofNullable(projectionsByWorkerId.get(normalizedWorkerId));
    }

    public List<WorkerStateProjection> projections() {
        return projectionsByWorkerId.values().stream()
                .sorted(java.util.Comparator.comparing(WorkerStateProjection::workerId))
                .toList();
    }

    private WorkerStateProjectionResult accepted(WorkerStateReport report,
                                                 WorkerStateProjection projection,
                                                 String reason) {
        return new WorkerStateProjectionResult(
                WorkerStateProjectionStatus.ACCEPTED,
                report.workerId(),
                report.stateVersion(),
                true,
                projection,
                reason
        );
    }

    private static WorkerStateProjection projectionFrom(WorkerStateReport report,
                                                        Instant acceptedAt,
                                                        List<WorkerStateReport> recentReports) {
        return new WorkerStateProjection(
                report.workerId(),
                report.stateVersion(),
                report.state(),
                report.reason(),
                report.observedAt(),
                acceptedAt,
                recentReports
        );
    }

    private List<WorkerStateReport> boundedHistory(WorkerStateReport report, List<WorkerStateReport> previous) {
        Deque<WorkerStateReport> reports = new ArrayDeque<>();
        reports.add(report);
        if (previous != null) {
            for (WorkerStateReport item : previous) {
                if (reports.size() >= recentHistoryLimit) {
                    break;
                }
                reports.addLast(item);
            }
        }
        return List.copyOf(new ArrayList<>(reports));
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
