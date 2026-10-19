package com.xa.mass.server.runtimeview;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupBindingsResponse;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupBindingsDirectoryResponse;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class WorkerGroupBindingsQueryService {
    private final WorkerResourceCatalog workers;
    private final WorkerMatchingCatalog matching;
    private final WorkerGroupBindings bindings;
    private final ProjectDirectory projects;
    private final List<String> startupGroups;

    public WorkerGroupBindingsQueryService(WorkerResourceCatalog workers, WorkerMatchingCatalog matching,
                                          WorkerGroupBindings bindings, ProjectDirectory projects) {
        this.workers = workers; this.matching = matching; this.bindings = bindings; this.projects = projects;
        var ids = new java.util.TreeSet<>(bindings.matchingGroups().keySet());
        projects.projects().values().stream().filter(p -> "declared".equals(p.workerGroupSource()))
                .forEach(p -> ids.addAll(p.workerGroupIds()));
        startupGroups = List.copyOf(ids);
    }

    public WorkerGroupBindingsDirectoryResponse directory() {
        return new WorkerGroupBindingsDirectoryResponse(startupGroups, Instant.now());
    }

    public WorkerGroupBindingsResponse load(String groupId, String requestId) {
        if (groupId == null || groupId.isBlank()) throw new ServerException(ServerErrorCode.MALFORMED_REQUEST,
                "workerBindings.load", null, null);
        try {
            var descriptor = workers.getWorkerGroupDescriptors(List.of(groupId)).get(groupId);
            if (descriptor != null && !groupId.equals(descriptor.workerGroupId()))
                throw new IllegalStateException("WorkerGroup descriptor identity differs");
            var group = bindings.group(groupId);
            return new WorkerGroupBindingsResponse(groupId, descriptor == null ? null : new WorkerGroupView(groupId,
                    descriptor.attributes(), descriptor.eventCodes().stream().sorted().toList()), matching.capabilities(groupId),
                    group.fixedWindowPools(), projects.projects().values().stream()
                            .filter(p -> "declared".equals(p.workerGroupSource()) ? p.workerGroupIds().contains(groupId) : descriptor != null)
                            .sorted(java.util.Comparator.comparing(ProjectDirectory.ProjectView::projectId))
                            .map(p -> new WorkerGroupBindingsResponse.Project(p.projectId(), p.workerGroupSource())).toList(), Instant.now());
        } catch (RuntimeException failure) {
            throw RuntimeViewService.unavailable("workerBindings.load", groupId, requestId, failure);
        }
    }
}
