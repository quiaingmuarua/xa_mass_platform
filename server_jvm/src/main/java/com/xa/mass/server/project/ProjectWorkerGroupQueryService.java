package com.xa.mass.server.project;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Project resource evidence. Only the explicit default Project traverses the registered Group directory. */
@Service
public final class ProjectWorkerGroupQueryService {
    private final ProjectDirectory projects;
    private final WorkerResourceCatalog workers;
    private final WorkerMatchingCatalog matching;

    public ProjectWorkerGroupQueryService(ProjectDirectory projects, WorkerResourceCatalog workers,
            WorkerMatchingCatalog matching) {
        this.projects = projects;
        this.workers = workers;
        this.matching = matching;
    }

    public List<ProjectWorkerGroup> list(String projectId) {
        var project = projects.require(projectId);
        var ids = project.workerGroupIds();
        try {
            var result = new ArrayList<ProjectWorkerGroup>();
            if ("registered".equals(project.workerGroupSource())) {
                var directory = new java.util.TreeMap<String, WorkerResourceCatalog.WorkerGroupDescriptor>();
                var visited = new java.util.HashSet<String>();
                String cursor = "0";
                do {
                    if (!visited.add(cursor)) throw new IllegalStateException("Group directory cursor repeated");
                    var page = workers.scanWorkerGroupDescriptors(cursor, 100);
                    for (var row : page.groups().entrySet()) {
                        if (directory.containsKey(row.getKey()) && !java.util.Objects.equals(directory.get(row.getKey()), row.getValue()))
                            throw new IllegalStateException("Group changed during directory read");
                        directory.put(row.getKey(), row.getValue());
                    }
                    cursor = page.nextCursor();
                } while (!"0".equals(cursor));
                directory.forEach((id, descriptor) -> result.add(view(id, descriptor)));
                return List.copyOf(result);
            }
            for (int offset = 0; offset < ids.size(); offset += 100) {
                var batch = ids.subList(offset, Math.min(offset + 100, ids.size()));
                var descriptors = workers.getWorkerGroupDescriptors(batch);
                for (String id : batch) {
                    var descriptor = descriptors.get(id);
                    result.add(view(id, descriptor));
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "project.listWorkerGroups", null, error);
        }
    }

    private ProjectWorkerGroup view(String id, @Nullable WorkerGroupDescriptor descriptor) {
        if (descriptor != null && !id.equals(descriptor.workerGroupId()))
            throw new IllegalStateException("WorkerGroup descriptor identity differs");
        return new ProjectWorkerGroup(id, descriptor == null ? null : new WorkerGroupView(id,
                descriptor.attributes(), descriptor.eventCodes().stream().sorted().toList()), matching.capabilities(id));
    }

    public record ProjectWorkerGroup(String workerGroupId, @Nullable WorkerGroupView workerGroup,
            WorkerMatchingCatalog.Capabilities matching) {}
}
