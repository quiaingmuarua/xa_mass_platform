package com.xa.mass.server.project;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.runtimeview.WorkerGroupView;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Project-bounded resource evidence; no discovery, initialization or supply operations. */
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
        var ids = projects.require(projectId).workerGroupIds();
        try {
            var result = new ArrayList<ProjectWorkerGroup>();
            for (int offset = 0; offset < ids.size(); offset += 100) {
                var batch = ids.subList(offset, Math.min(offset + 100, ids.size()));
                var descriptors = workers.getWorkerGroupDescriptors(batch);
                for (String id : batch) {
                    var descriptor = descriptors.get(id);
                    if (descriptor != null && !id.equals(descriptor.workerGroupId()))
                        throw new IllegalStateException("WorkerGroup descriptor identity differs");
                    result.add(new ProjectWorkerGroup(id, descriptor == null ? null : new WorkerGroupView(id,
                            descriptor.attributes(), descriptor.eventCodes().stream().sorted().toList()),
                            matching.capabilities(id)));
                }
            }
            return List.copyOf(result);
        } catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "project.listWorkerGroups", null, error);
        }
    }

    public record ProjectWorkerGroup(String workerGroupId, @Nullable WorkerGroupView workerGroup,
            WorkerMatchingCatalog.Capabilities matching) {}
}
