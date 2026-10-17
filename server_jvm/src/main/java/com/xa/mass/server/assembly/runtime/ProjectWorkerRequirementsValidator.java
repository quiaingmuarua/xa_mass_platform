package com.xa.mass.server.assembly.runtime;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.project.ProjectWorkerRequirements;
import com.xa.mass.workermatching.MatchingGroup;
import java.util.*;

/** One startup check over caller-known Groups, after host registration and before Adapter startup. */
final class ProjectWorkerRequirementsValidator {
    private static final int GROUP_READ_BATCH_SIZE = 100;
    private final List<ProjectWorkerRequirements> requirements;
    private final WorkerResourceCatalog workers;
    private final WorkerGroupBindings matching;

    ProjectWorkerRequirementsValidator(ProjectDirectory projects, WorkerResourceCatalog workers,
            WorkerGroupBindings matching, List<ProjectWorkerRequirements> requirements) {
        this.workers = Objects.requireNonNull(workers);
        this.matching = Objects.requireNonNull(matching);
        this.requirements = requirements.stream().sorted(Comparator.comparing(ProjectWorkerRequirements::projectId)).toList();
        var pairs = new HashSet<Map.Entry<String, String>>();
        for (var requirement : this.requirements) {
            for (String group : requirement.requirementsByGroup().keySet()) {
                if (!pairs.add(Map.entry(requirement.projectId(), group)))
                    throw failure(requirement, group, "duplicate Project/Group requirements", null);
                var project = projects.projects().get(requirement.projectId());
                if (project == null) throw failure(requirement, group, "Project is not declared", null);
                if (!project.allowsWorkerGroup(group))
                    throw failure(requirement, group, "Group is not declared by Project", null);
            }
        }
    }

    void validate() {
        var byGroup = new LinkedHashMap<String, List<ProjectWorkerRequirements>>();
        for (var requirement : requirements) {
            for (String group : requirement.requirementsByGroup().keySet()) {
                var available = matching.matchingGroups().getOrDefault(group, new MatchingGroup(Set.of(), Set.of()));
                requireSubset(requirement, group, "Pools", requirement.requirementsByGroup().get(group).pools(), available.pools());
                requireSubset(requirement, group, "functions", requirement.requirementsByGroup().get(group).functions(), available.functions());
                byGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(requirement);
            }
        }
        matching.requiredEvents().keySet().forEach(group -> byGroup.putIfAbsent(group, List.of()));
        var ids = List.copyOf(byGroup.keySet());
        for (int start = 0; start < ids.size(); start += GROUP_READ_BATCH_SIZE) {
            var batch = ids.subList(start, Math.min(start + GROUP_READ_BATCH_SIZE, ids.size()));
            Map<String, WorkerGroupDescriptor> descriptors;
            try {
                descriptors = Objects.requireNonNull(workers.getWorkerGroupDescriptors(batch));
            } catch (RuntimeException unavailable) {
                var first = byGroup.get(batch.getFirst());
                if (first.isEmpty()) throw bindingFailure(batch.getFirst(), "Group descriptor read unavailable", unavailable);
                throw failure(first.getFirst(), batch.getFirst(), "Group descriptor read unavailable", unavailable);
            }
            for (String group : batch) {
                var descriptor = descriptors.get(group);
                if (matching.requiredEvents().containsKey(group)) {
                    if (descriptor == null) throw bindingFailure(group, "Group is not registered", null);
                    var missing = new TreeSet<>(matching.requiredEvents().get(group));
                    missing.removeAll(descriptor.eventCodes());
                    if (!missing.isEmpty()) throw bindingFailure(group, "missing feedback events: " + missing, null);
                }
                for (var requirement : byGroup.get(group)) {
                    if (descriptor == null) throw failure(requirement, group, "Group is not registered", null);
                    requireSubset(requirement, group, "events", requirement.requirementsByGroup().get(group).eventCodes(), descriptor.eventCodes());
                }
            }
        }
    }

    private static void requireSubset(ProjectWorkerRequirements requirement, String group, String kind, Set<String> required, Set<String> available) {
        var missing = new TreeSet<>(required);
        missing.removeAll(available);
        if (!missing.isEmpty()) throw failure(requirement, group, "missing " + kind + ": " + missing, null);
    }

    private static IllegalStateException failure(ProjectWorkerRequirements requirement, String group, String detail, Throwable cause) {
        return new IllegalStateException("operation=project.dependencies.validate project=" + requirement.projectId()
                + " group=" + group + " " + detail, cause);
    }

    private IllegalStateException bindingFailure(String group, String detail, Throwable cause) {
        var binding = matching.group(group);
        return new IllegalStateException("operation=workerBindings.dependencies.validate group=" + group
                + " pools=" + binding.fixedWindowPools().stream().map(WorkerGroupBindings.WindowPool::poolName).toList()
                + " feedbacks=" + binding.feedbacks().stream().map(WorkerGroupBindings.Feedback::name).toList() + " " + detail, cause);
    }
}
