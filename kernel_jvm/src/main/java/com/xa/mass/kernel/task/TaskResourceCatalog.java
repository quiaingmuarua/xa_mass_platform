package com.xa.mass.kernel.task;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public interface TaskResourceCatalog {

    Map<String, @Nullable TaskDescriptor> loadTaskAllocationDescriptors(
            List<String> taskIds
    );

    /** First-created order, newest first; closed Tasks remain in the directory. */
    default ProjectTaskPage listProjectTasks(String projectId, int limit) {
        throw new com.xa.mass.kernel.KernelOperationNotImplementedException(
                "TaskResourceCatalog", "listProjectTasks");
    }

    record ProjectTaskEntry(String taskId, long createdAtMillis) {}

    /** Point lookup in the first-created project directory, independent of list windows. */
    default @Nullable ProjectTaskEntry getProjectTask(String projectId, String taskId) {
        throw new com.xa.mass.kernel.KernelOperationNotImplementedException("TaskResourceCatalog", "getProjectTask");
    }

    record ProjectTaskPage(List<ProjectTaskEntry> tasks, boolean truncated) {
        public ProjectTaskPage { tasks = List.copyOf(tasks); }
    }
}
