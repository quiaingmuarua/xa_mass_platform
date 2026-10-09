package com.xa.mass.server.assembly.runtime;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.assembly.matching.MatchingProperties;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectWorkerRequirements;
import com.xa.mass.workermatching.MatchingGroup;
import java.util.*;

/** One startup check over caller-known Groups, after host registration and before Project initialization. */
final class ProjectWorkerRequirementsValidator {
    private static final int GROUP_READ_BATCH_SIZE = 100;
    private final List<ProjectWorkerRequirements> requirements;
    private final WorkerResourceCatalog workers;
    private final MatchingProperties matching;

    ProjectWorkerRequirementsValidator(ProjectDirectory projects, WorkerResourceCatalog workers,
            MatchingProperties matching, List<ProjectWorkerRequirements> requirements) {
        this.workers = Objects.requireNonNull(workers);
        this.matching = Objects.requireNonNull(matching);
        this.requirements = requirements.stream().sorted(Comparator.comparing(ProjectWorkerRequirements::projectId)
                .thenComparing(ProjectWorkerRequirements::workerGroupId)).toList();
        var pairs = new HashSet<Map.Entry<String, String>>();
        for (var requirement : this.requirements) {
            if (!pairs.add(Map.entry(requirement.projectId(), requirement.workerGroupId())))
                throw failure(requirement, "duplicate Project/Group requirements", null);
            var project = projects.projects().get(requirement.projectId());
            if (project == null) throw failure(requirement, "Project is not declared", null);
            if (!project.managedTaskIds().containsKey(requirement.workerGroupId()))
                throw failure(requirement, "Group is not declared by Project", null);
        }
    }

    void validate() {
        var byGroup = new LinkedHashMap<String, List<ProjectWorkerRequirements>>();
        for (var requirement : requirements) {
            var available = matching.groups().getOrDefault(requirement.workerGroupId(), new MatchingGroup(Set.of(), Set.of()));
            requireSubset(requirement, "Pools", requirement.pools(), available.pools());
            requireSubset(requirement, "functions", requirement.functions(), available.functions());
            byGroup.computeIfAbsent(requirement.workerGroupId(), ignored -> new ArrayList<>()).add(requirement);
        }
        var ids = List.copyOf(byGroup.keySet());
        for (int start = 0; start < ids.size(); start += GROUP_READ_BATCH_SIZE) {
            var batch = ids.subList(start, Math.min(start + GROUP_READ_BATCH_SIZE, ids.size()));
            Map<String, WorkerGroupDescriptor> descriptors;
            try {
                descriptors = Objects.requireNonNull(workers.getWorkerGroupDescriptors(batch));
            } catch (RuntimeException unavailable) {
                throw failure(byGroup.get(batch.getFirst()).getFirst(), "Group descriptor read unavailable", unavailable);
            }
            for (String group : batch) {
                var descriptor = descriptors.get(group);
                for (var requirement : byGroup.get(group)) {
                    if (descriptor == null) throw failure(requirement, "Group is not registered", null);
                    requireSubset(requirement, "events", requirement.eventCodes(), descriptor.eventCodes());
                }
            }
        }
    }

    private static void requireSubset(ProjectWorkerRequirements requirement, String kind, Set<String> required, Set<String> available) {
        var missing = new TreeSet<>(required);
        missing.removeAll(available);
        if (!missing.isEmpty()) throw failure(requirement, "missing " + kind + ": " + missing, null);
    }

    private static IllegalStateException failure(ProjectWorkerRequirements requirement, String detail, Throwable cause) {
        return new IllegalStateException("operation=project.dependencies.validate project=" + requirement.projectId()
                + " group=" + requirement.workerGroupId() + " " + detail, cause);
    }
}
