package com.xa.mass.sdk;

import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.runtime.worker.EventBinding;
import com.xa.mass.runtime.worker.WorkerGroupRecord;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * SDK/intake resolver for event-backed task ergonomics.
 *
 * <p>The scheduling kernel consumes explicit worker group selectors only. This
 * resolver materializes event metadata into that selector before work enters
 * assignment.</p>
 */
final class WorkerGroupSelectorResolver {

    private WorkerGroupSelectorResolver() {
    }

    static Map<String, Object> resolveEventBackedSelector(Map<String, Object> sharedConfig,
                                                          String projectCode,
                                                          String eventCode,
                                                          Collection<WorkerGroupRecord> workerGroups) {
        LinkedHashMap<String, Object> resolved = new LinkedHashMap<>(
                sharedConfig == null ? Map.of() : sharedConfig
        );
        if (!TaskSharedConfig.workerGroupSelector(resolved).isEmpty()) {
            return new LinkedHashMap<>(resolved);
        }

        String normalizedProjectCode = blankToNull(projectCode);
        String normalizedEventCode = blankToNull(eventCode);
        if (normalizedProjectCode == null || normalizedEventCode == null) {
            return new LinkedHashMap<>(resolved);
        }

        List<String> groupIds = matchingGroupIds(workerGroups, normalizedProjectCode, normalizedEventCode);
        if (groupIds.isEmpty()) {
            throw new IllegalArgumentException("No worker group selector resolved for project/eventCode: "
                    + normalizedProjectCode + "/" + normalizedEventCode);
        }
        if (groupIds.size() == 1) {
            resolved.put(TaskSharedConfig.WORKER_GROUP_ID, groupIds.getFirst());
            resolved.remove(TaskSharedConfig.WORKER_GROUP_IDS);
        } else {
            resolved.put(TaskSharedConfig.WORKER_GROUP_IDS, groupIds);
            resolved.remove(TaskSharedConfig.WORKER_GROUP_ID);
        }
        return new LinkedHashMap<>(resolved);
    }

    static void requireExplicitTargetWorkerBinding(Map<String, Object> sharedConfig) {
        String targetWorkerId = TaskSharedConfig.stringValue(sharedConfig, TaskSharedConfig.TARGET_WORKER_ID);
        if (targetWorkerId != null && TaskSharedConfig.workerGroupSelector(sharedConfig).isEmpty()) {
            throw new IllegalArgumentException("targetWorkerId requires explicit workerGroupId or workerGroupIds");
        }
    }

    private static List<String> matchingGroupIds(Collection<WorkerGroupRecord> workerGroups,
                                                 String projectCode,
                                                 String eventCode) {
        if (workerGroups == null || workerGroups.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        for (WorkerGroupRecord group : workerGroups) {
            if (group == null) {
                continue;
            }
            for (EventBinding binding : group.eventBindings()) {
                if (eventCode.equals(binding.eventCode()) && binding.projectCodes().contains(projectCode)) {
                    matches.add(group.groupId());
                }
            }
        }
        return matches.isEmpty() ? List.of() : List.copyOf(matches);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
