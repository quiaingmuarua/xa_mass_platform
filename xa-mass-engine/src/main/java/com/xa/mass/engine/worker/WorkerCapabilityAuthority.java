package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;
import com.xa.mass.runtime.worker.WorkerCapabilityReportStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        LinkedHashMap<String, WorkerGroupRecord> groupsById = new LinkedHashMap<>();
        if (declaredGroups != null) {
            for (WorkerGroupRecord group : declaredGroups) {
                if (group != null) {
                    groupsById.put(group.groupId(), group);
                }
            }
        }
        List<Worker> effectiveWorkers = effectiveWorkers(registrationRows, groupsById);
        return WorkerRegistrySnapshot.from(
                groupsById.values(),
                effectiveWorkers,
                workerEventKeysByWorkerId(registrationRows, groupsById)
        );
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
            return result(WorkerCapabilityReportStatus.UNKNOWN_WORKER, report, false,
                    "worker is not registered");
        }

        WorkerCapabilityReport existing = reportsByWorkerId.get(report.workerId());
        if (existing != null) {
            if (report.capabilityVersion() < existing.capabilityVersion()) {
                return result(WorkerCapabilityReportStatus.STALE, report, false,
                        "capability version is stale");
            }
            if (report.capabilityVersion() == existing.capabilityVersion()) {
                if (existing.equals(report)) {
                    return result(WorkerCapabilityReportStatus.IDEMPOTENT, report, false,
                            "capability report already applied");
                }
                return result(WorkerCapabilityReportStatus.CONFLICT, report, false,
                        "same capability version has different payload");
            }
        }

        reportsByWorkerId.put(report.workerId(), report);
        return result(WorkerCapabilityReportStatus.ACCEPTED, report, true,
                "capability report accepted");
    }

    private List<Worker> effectiveWorkers(Collection<Worker> registrationRows) {
        return effectiveWorkers(registrationRows, Map.of());
    }

    private List<Worker> effectiveWorkers(Collection<Worker> registrationRows,
                                          Map<String, WorkerGroupRecord> groupsById) {
        if (registrationRows == null || registrationRows.isEmpty()) {
            return List.of();
        }
        List<Worker> effective = new ArrayList<>();
        for (Worker worker : registrationRows) {
            if (worker == null || normalizeNullable(worker.getWorkerId()) == null) {
                continue;
            }
            WorkerCapabilityReport report = reportsByWorkerId.get(worker.getWorkerId().trim());
            effective.add(report == null ? worker : effectiveWorker(worker, report, groupsById));
        }
        return effective;
    }

    private Worker effectiveWorker(Worker worker,
                                   WorkerCapabilityReport report,
                                   Map<String, WorkerGroupRecord> groupsById) {
        Worker effective = copyWorker(worker);
        if (!report.availableEventCodes().isEmpty()) {
            effective.setSupportedEventCodes(approvedEventCodes(worker, report, groupsById));
        }
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

    private static List<String> approvedEventCodes(Worker worker,
                                                   WorkerCapabilityReport report,
                                                   Map<String, WorkerGroupRecord> groupsById) {
        WorkerGroupRecord group = groupFor(worker, groupsById);
        if (group == null) {
            return List.of();
        }
        Set<String> approved = group.eventCodes();
        if (approved.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        for (String eventCode : report.availableEventCodes()) {
            String normalized = normalizeNullable(eventCode);
            if (normalized != null && approved.contains(normalized)) {
                effective.add(normalized);
            }
        }
        return effective.isEmpty() ? List.of() : List.copyOf(effective);
    }

    private static WorkerGroupRecord groupFor(Worker worker, Map<String, WorkerGroupRecord> groupsById) {
        String groupId = worker == null ? null : normalizeNullable(worker.getWorkerGroupId());
        return groupId == null || groupsById == null ? null : groupsById.get(groupId);
    }

    private Map<String, Set<EventKey>> workerEventKeysByWorkerId(Collection<Worker> registrationRows,
                                                                  Map<String, WorkerGroupRecord> groupsById) {
        if (reportsByWorkerId.isEmpty() || registrationRows == null || registrationRows.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Set<EventKey>> scopes = new LinkedHashMap<>();
        for (Worker worker : registrationRows) {
            String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
            if (workerId == null) {
                continue;
            }
            WorkerCapabilityReport report = reportsByWorkerId.get(workerId);
            if (report == null) {
                continue;
            }
            scopes.put(workerId, approvedEventKeys(worker, report, groupsById));
        }
        return scopes.isEmpty() ? Map.of() : Map.copyOf(scopes);
    }

    private static Set<EventKey> approvedEventKeys(Worker worker,
                                                   WorkerCapabilityReport report,
                                                   Map<String, WorkerGroupRecord> groupsById) {
        WorkerGroupRecord group = groupFor(worker, groupsById);
        if (group == null || group.eventBindings().isEmpty()) {
            return Set.of();
        }
        Set<String> approvedEventCodes = new LinkedHashSet<>(approvedEventCodes(worker, report, groupsById));
        if (approvedEventCodes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<EventKey> eventKeys = new LinkedHashSet<>();
        for (EventBinding binding : group.eventBindings()) {
            if (approvedEventCodes.contains(binding.eventCode())) {
                eventKeys.addAll(binding.eventKeys());
            }
        }
        return eventKeys.isEmpty() ? Set.of() : Set.copyOf(eventKeys);
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
                                                       String reason) {
        return new WorkerCapabilityReportResult(status, report.workerId(), report.capabilityVersion(),
                snapshotChanged, reason);
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
