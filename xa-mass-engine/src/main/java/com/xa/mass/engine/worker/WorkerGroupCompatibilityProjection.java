package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Temporary projection from current worker-level compatibility fields into
 * WorkerGroup snapshot truth.
 *
 * <p>This is a migration input for WG-3 wiring. WorkerGroup indexing stays
 * separate from runtime load, reachability, and resource admission.</p>
 */
final class WorkerGroupCompatibilityProjection {

    private WorkerGroupCompatibilityProjection() {
    }

    static WorkerRegistrySnapshot snapshotFromWorkers(Collection<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return WorkerRegistrySnapshot.empty();
        }
        LinkedHashMap<String, GroupDraft> drafts = new LinkedHashMap<>();
        for (Worker worker : workers) {
            String groupId = worker == null ? null : normalizeNullable(worker.getWorkerGroupId());
            if (groupId == null) {
                continue;
            }
            GroupDraft draft = drafts.computeIfAbsent(groupId, GroupDraft::new);
            draft.observe(worker);
        }
        List<WorkerGroupRecord> groups = drafts.values().stream()
                .map(GroupDraft::toRecord)
                .toList();
        return WorkerRegistrySnapshot.from(groups, workers);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class GroupDraft {
        private final String groupId;
        private String adapterNodeId;
        private int defaultMaxConcurrentWork = 1;
        private final LinkedHashMap<String, LinkedHashSet<String>> projectsByEventCode = new LinkedHashMap<>();
        private final LinkedHashSet<String> projectCodes = new LinkedHashSet<>();
        private final LinkedHashMap<String, String> defaultAttributes = new LinkedHashMap<>();

        private GroupDraft(String groupId) {
            this.groupId = groupId;
        }

        private void observe(Worker worker) {
            if (adapterNodeId == null) {
                adapterNodeId = normalizeNullable(worker.getAdapterId());
            }
            defaultMaxConcurrentWork = Math.max(defaultMaxConcurrentWork, worker.getMaxConcurrentWork());
            if (defaultAttributes.isEmpty() && worker.getAttributes() != null) {
                for (Map.Entry<String, String> entry : worker.getAttributes().entrySet()) {
                    String key = normalizeNullable(entry.getKey());
                    String value = normalizeNullable(entry.getValue());
                    if (key != null && value != null) {
                        defaultAttributes.put(key, value);
                    }
                }
            }

            List<String> supportedEventCodes = worker.getSupportedEventCodes() == null
                    ? List.of()
                    : worker.getSupportedEventCodes();
            List<String> supportedProjects = worker.getSupportedProjects() == null
                    ? List.of()
                    : worker.getSupportedProjects();
            for (String projectValue : supportedProjects) {
                String projectCode = normalizeNullable(projectValue);
                if (projectCode != null) {
                    projectCodes.add(projectCode);
                }
            }
            for (String eventCodeValue : supportedEventCodes) {
                String eventCode = normalizeNullable(eventCodeValue);
                if (eventCode == null) {
                    continue;
                }
                LinkedHashSet<String> projects = projectsByEventCode.computeIfAbsent(
                        eventCode,
                        ignored -> new LinkedHashSet<>()
                );
                for (String projectValue : supportedProjects) {
                    String projectCode = normalizeNullable(projectValue);
                    if (projectCode != null) {
                        projects.add(projectCode);
                    }
                }
            }
        }

        private WorkerGroupRecord toRecord() {
            List<EventBinding> eventBindings = new ArrayList<>();
            for (Map.Entry<String, LinkedHashSet<String>> entry : projectsByEventCode.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    eventBindings.add(EventBinding.of(entry.getKey(), List.copyOf(entry.getValue())));
                }
            }
            return WorkerGroupRecord.builder(groupId)
                    .adapterNodeId(adapterNodeId)
                    .eventBindings(eventBindings)
                    .projectCodes(projectCodes)
                    .defaultAttributes(defaultAttributes)
                    .defaultMaxConcurrentWork(defaultMaxConcurrentWork)
                    .build();
        }
    }
}
