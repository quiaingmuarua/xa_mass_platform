package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Owns effective worker capability composition for the scheduling candidate
 * source.
 *
 * <p>EWC-3A keeps current behavior: worker registration rows and worker-level
 * compatibility fields remain the only input. Future worker capability reports
 * must enter through this owner before a new immutable
 * {@link WorkerRegistrySnapshot} is published.</p>
 */
public final class WorkerCapabilityAuthority {

    private final LinkedHashMap<String, WorkerCapabilityReport> reportsByWorkerId = new LinkedHashMap<>();

    public synchronized WorkerRegistrySnapshot composeSnapshot(Collection<Worker> registrationRows) {
        return WorkerGroupCompatibilityProjection.snapshotFromWorkers(effectiveWorkers(registrationRows));
    }

    public synchronized WorkerCapabilityReportResult applyReport(WorkerCapabilityReport report,
                                                                 Collection<Worker> registrationRows) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }

        Worker registrationRow = registrationRow(report.workerId(), registrationRows);
        if (registrationRow == null) {
            return result(WorkerCapabilityReportStatus.UNKNOWN_WORKER, report, false, null,
                    "worker is not registered");
        }

        WorkerCapabilityReport existing = reportsByWorkerId.get(report.workerId());
        if (existing != null) {
            if (report.capabilityVersion() < existing.capabilityVersion()) {
                return result(WorkerCapabilityReportStatus.STALE, report, false, null,
                        "capability version is stale");
            }
            if (report.capabilityVersion() == existing.capabilityVersion()) {
                if (existing.equals(report)) {
                    return result(WorkerCapabilityReportStatus.IDEMPOTENT, report, false, null,
                            "capability report already applied");
                }
                return result(WorkerCapabilityReportStatus.CONFLICT, report, false, null,
                        "same capability version has different payload");
            }
        }

        reportsByWorkerId.put(report.workerId(), report);
        return result(WorkerCapabilityReportStatus.ACCEPTED, report, true, composeSnapshot(registrationRows),
                "capability report accepted");
    }

    private List<Worker> effectiveWorkers(Collection<Worker> registrationRows) {
        if (registrationRows == null || registrationRows.isEmpty()) {
            return List.of();
        }
        List<Worker> effective = new ArrayList<>();
        for (Worker worker : registrationRows) {
            if (worker == null || normalizeNullable(worker.getWorkerId()) == null) {
                continue;
            }
            WorkerCapabilityReport report = reportsByWorkerId.get(worker.getWorkerId().trim());
            effective.add(report == null ? worker : effectiveWorker(worker, report));
        }
        return effective;
    }

    private Worker effectiveWorker(Worker worker, WorkerCapabilityReport report) {
        Worker effective = copyWorker(worker);
        effective.setSupportedEventCodes(effectiveEventCodes(worker.getSupportedEventCodes(), report.availableEventCodes()));
        if (!report.schedulingAttributes().isEmpty()) {
            LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
            if (worker.getAttributes() != null) {
                attributes.putAll(worker.getAttributes());
            }
            attributes.putAll(report.schedulingAttributes());
            effective.setAttributes(attributes);
        }
        if (report.agentVersion() != null) {
            effective.setAgentVersion(report.agentVersion());
        }
        return effective;
    }

    private static List<String> effectiveEventCodes(List<String> registrationCodes, List<String> reportCodes) {
        if (reportCodes == null || reportCodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        if (registrationCodes != null) {
            for (String code : registrationCodes) {
                String normalized = normalizeNullable(code);
                if (normalized != null) {
                    allowed.add(normalized);
                }
            }
        }
        if (allowed.isEmpty()) {
            return List.of();
        }
        List<String> effective = new ArrayList<>();
        for (String code : reportCodes) {
            String normalized = normalizeNullable(code);
            if (normalized != null && allowed.contains(normalized)) {
                effective.add(normalized);
            }
        }
        return effective.isEmpty() ? List.of() : List.copyOf(effective);
    }

    private static Worker registrationRow(String workerId, Collection<Worker> registrationRows) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null || registrationRows == null) {
            return null;
        }
        for (Worker worker : registrationRows) {
            if (worker != null && normalizedWorkerId.equals(normalizeNullable(worker.getWorkerId()))) {
                return worker;
            }
        }
        return null;
    }

    private static WorkerCapabilityReportResult result(WorkerCapabilityReportStatus status,
                                                       WorkerCapabilityReport report,
                                                       boolean snapshotChanged,
                                                       WorkerRegistrySnapshot snapshot,
                                                       String reason) {
        return new WorkerCapabilityReportResult(status, report.workerId(), report.capabilityVersion(),
                snapshotChanged, snapshot, reason);
    }

    private static Worker copyWorker(Worker source) {
        Worker copy = new Worker();
        copy.setWorkerId(source.getWorkerId());
        if (source.getStatus() != null) {
            copy.setStatus(source.getStatus());
        }
        copy.setAgentVersion(source.getAgentVersion());
        copy.setLastHeartbeat(source.getLastHeartbeat());
        copy.setSupportedProjects(source.getSupportedProjects());
        copy.setSupportedEventCodes(source.getSupportedEventCodes());
        copy.setWorkerGroupId(source.getWorkerGroupId());
        copy.setAdapterId(source.getAdapterId());
        copy.setOnlineStrategy(source.getOnlineStrategy());
        copy.setMaxConcurrentWork(source.getMaxConcurrentWork());
        copy.setAttributes(source.getAttributes());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        return copy;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
