package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.resource.EventBinding;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;

import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportStatus;

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

    public synchronized WorkerRegistrySnapshot composeSnapshot(Collection<WorkerDeclarationRecord> registrationRows) {
        return WorkerRegistrySnapshot.from(List.of(), effectiveWorkers(registrationRows));
    }

    public synchronized WorkerRegistrySnapshot composeSnapshot(Collection<WorkerDeclarationRecord> registrationRows,
                                                               Collection<WorkerGroupRecord> declaredGroups) {
        LinkedHashMap<String, WorkerGroupRecord> groupsById = new LinkedHashMap<>();
        if (declaredGroups != null) {
            for (WorkerGroupRecord group : declaredGroups) {
                if (group != null) {
                    groupsById.put(group.groupId(), group);
                }
            }
        }
        List<WorkerDeclarationRecord> effectiveWorkers = effectiveWorkers(registrationRows, groupsById);
        return WorkerRegistrySnapshot.from(
                groupsById.values(),
                effectiveWorkers,
                workerEventKeysByWorkerId(registrationRows, groupsById)
        );
    }

    public synchronized WorkerCapabilityReportResult applyReport(WorkerCapabilityReport report,
                                                                 Collection<WorkerDeclarationRecord> registrationRows) {
        return applyReport(report, registrationRows, List.of());
    }

    public synchronized WorkerCapabilityReportResult applyReport(WorkerCapabilityReport report,
                                                                 Collection<WorkerDeclarationRecord> registrationRows,
                                                                 Collection<WorkerGroupRecord> declaredGroups) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }

        WorkerDeclarationRecord registrationRow = registrationRow(report.workerId(), registrationRows);
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

    private List<WorkerDeclarationRecord> effectiveWorkers(Collection<WorkerDeclarationRecord> registrationRows) {
        return effectiveWorkers(registrationRows, Map.of());
    }

    private List<WorkerDeclarationRecord> effectiveWorkers(Collection<WorkerDeclarationRecord> registrationRows,
                                                           Map<String, WorkerGroupRecord> groupsById) {
        if (registrationRows == null || registrationRows.isEmpty()) {
            return List.of();
        }
        List<WorkerDeclarationRecord> effective = new ArrayList<>();
        for (WorkerDeclarationRecord worker : registrationRows) {
            if (worker == null || normalizeNullable(worker.workerId()) == null) {
                continue;
            }
            WorkerCapabilityReport report = reportsByWorkerId.get(worker.workerId().trim());
            effective.add(report == null ? worker : effectiveWorker(worker, report));
        }
        return effective;
    }

    private WorkerDeclarationRecord effectiveWorker(WorkerDeclarationRecord worker,
                                                    WorkerCapabilityReport report) {
        if (!report.schedulingAttributes().isEmpty()) {
            LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
            attributes.putAll(worker.attributes());
            attributes.putAll(report.schedulingAttributes());
            return copyWorker(worker, report.agentVersion(), attributes);
        }
        return copyWorker(worker, report.agentVersion(), worker.attributes());
    }

    private static List<String> approvedEventCodes(WorkerDeclarationRecord worker,
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

    private static WorkerGroupRecord groupFor(WorkerDeclarationRecord worker, Map<String, WorkerGroupRecord> groupsById) {
        String groupId = worker == null ? null : normalizeNullable(worker.workerGroupId());
        return groupId == null || groupsById == null ? null : groupsById.get(groupId);
    }

    private Map<String, Set<EventKey>> workerEventKeysByWorkerId(Collection<WorkerDeclarationRecord> registrationRows,
                                                                 Map<String, WorkerGroupRecord> groupsById) {
        if (reportsByWorkerId.isEmpty() || registrationRows == null || registrationRows.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Set<EventKey>> scopes = new LinkedHashMap<>();
        for (WorkerDeclarationRecord worker : registrationRows) {
            String workerId = worker == null ? null : normalizeNullable(worker.workerId());
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

    private static Set<EventKey> approvedEventKeys(WorkerDeclarationRecord worker,
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

    private static WorkerDeclarationRecord registrationRow(String workerId,
                                                           Collection<WorkerDeclarationRecord> registrationRows) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null || registrationRows == null) {
            return null;
        }
        for (WorkerDeclarationRecord worker : registrationRows) {
            if (worker != null && normalizedWorkerId.equals(normalizeNullable(worker.workerId()))) {
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

    private static WorkerDeclarationRecord copyWorker(WorkerDeclarationRecord source,
                                                      String reportAgentVersion,
                                                      Map<String, String> attributes) {
        return new WorkerDeclarationRecord(
                source.workerId(),
                source.workerGroupId(),
                source.transportHint(),
                reportAgentVersion == null ? source.agentVersion() : reportAgentVersion,
                source.maxConcurrentWork(),
                attributes
        );
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
