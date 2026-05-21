package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Owns effective worker capability composition for the scheduling candidate
 * source.
 *
 * <p>WorkerGroup declarations are the capability truth. Worker capability
 * reports still enter through this owner before a new immutable
 * {@link WorkerRegistrySnapshot} is published, but they must not create or
 * mutate WorkerGroup event bindings.</p>
 */
public final class WorkerCapabilityAuthority {

    private final LinkedHashMap<String, WorkerCapabilityReport> reportsByWorkerId = new LinkedHashMap<>();

    public synchronized WorkerRegistrySnapshot composeSnapshot(Collection<Worker> registrationRows) {
        return WorkerRegistrySnapshot.from(List.of(), effectiveWorkers(registrationRows));
    }

    public synchronized WorkerRegistrySnapshot composeSnapshot(Collection<Worker> registrationRows,
                                                               Collection<WorkerGroupRecord> declaredGroups) {
        List<Worker> effectiveWorkers = effectiveWorkers(registrationRows);
        LinkedHashMap<String, WorkerGroupRecord> groupsById = new LinkedHashMap<>();
        if (declaredGroups != null) {
            for (WorkerGroupRecord group : declaredGroups) {
                if (group != null) {
                    groupsById.put(group.groupId(), group);
                }
            }
        }
        return WorkerRegistrySnapshot.from(groupsById.values(), effectiveWorkers);
    }

    public synchronized WorkerCapabilityReportResult applyReport(WorkerCapabilityReport report,
                                                                 Collection<Worker> registrationRows) {
        return applyReport(report, registrationRows, List.of());
    }

    public synchronized WorkerCapabilityReportResult applyReport(WorkerCapabilityReport report,
                                                                 Collection<Worker> registrationRows,
                                                                 Collection<WorkerGroupRecord> declaredGroups) {
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
        return result(WorkerCapabilityReportStatus.ACCEPTED, report, true, composeSnapshot(registrationRows, declaredGroups),
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
        copy.setAdapterNodeId(source.getAdapterNodeId());
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
