package com.xa.mass.server.task.access;

import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskUserId;
import com.xa.mass.server.user.UserDirectory;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Explicit trusted-development caller; only assembly can supply an in-process Project identity. */
@io.swagger.v3.oas.annotations.media.Schema(hidden = true)
public final class TaskCaller {
    public static final String RAW_INPUT = "raw";
    public static final String INPUT_OWNER = "server.inputOwner";
    private final @Nullable String userId;
    private final String role;
    private final @Nullable String serviceProject;
    private final UserDirectory.@Nullable Role access;
    private final List<String> projectIds;

    private TaskCaller(UserDirectory.User user, UserDirectory.Role access) {
        this.userId = user.userId();
        this.role = user.role();
        if (!role.equals(access.id())) throw new IllegalArgumentException("Role differs from user directory");
        this.access = access;
        this.projectIds = List.copyOf(user.projectIds());
        this.serviceProject = null;
    }

    /** Called by the directory after admission; retains no mutable directory reference. */
    public static TaskCaller fromDirectory(UserDirectory.User user, UserDirectory.Role access) {
        return new TaskCaller(user, access);
    }

    private TaskCaller(ProjectDefinition project) {
        this.userId = null;
        this.role = "service";
        this.serviceProject = Objects.requireNonNull(project, "project").projectId();
        this.access = null;
        this.projectIds = List.of();
    }

    public static TaskCaller forProjectService(ProjectDefinition project) { return new TaskCaller(project); }

    public @Nullable String userId() { return userId; }
    public String role() { return role; }
    public String identityKey() { return serviceProject == null ? "user:" + userId : "service:" + serviceProject; }
    public boolean isService() { return serviceProject != null; }
    public void requireAdmin() { if (!"admin".equals(role)) throw forbidden(); }
    public void requirePermission(String permission) {
        if (access == null || !access.permissions().contains(permission)) throw forbidden();
    }
    public void requireProject(String projectId) {
        if (serviceProject != null) {
            if (!serviceProject.equals(projectId)) throw forbidden();
        } else if (!access.projectScope().equals("all") && !projectIds.contains(projectId)) throw forbidden();
    }
    public boolean canRead(@Nullable String creator) {
        return access != null && (access.taskScope().equals("all") || userId.equals(creator));
    }
    public void requireOwner(@Nullable String creator) { if (!canRead(creator)) throw forbidden(); }
    public void requireTask(TaskDescriptor task) {
        final TaskAttribution attribution;
        try { attribution = TaskAttribution.read(task.metadata()); }
        catch (IllegalArgumentException corrupt) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskAccess.require", null, corrupt);
        }
        if (attribution == null) { requireAdmin(); return; }
        requireProject(attribution.projectId());
        if (!isService()) requireOwner(attribution.userId());
    }
    public @Nullable String selectUser(@Nullable String requested) {
        final String normalized;
        try { normalized = requested == null ? null : TaskUserId.normalize(requested); }
        catch (IllegalArgumentException invalid) { throw invalidCaller(invalid); }
        if (access != null && access.taskScope().equals("own")) {
            if (normalized != null) requireOwner(normalized);
            return userId;
        }
        return normalized;
    }
    public void requireInput(TaskDescriptor task, String inputOwner) {
        requireTask(task);
        if (!Objects.requireNonNull(inputOwner, "inputOwner").equals(task.metadata().get(INPUT_OWNER)))
            throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "taskAccess.input", "Task input has another owner", null);
        if (serviceProject != null && !serviceProject.equals(inputOwner)) throw forbidden();
    }
    private static ServerException invalidCaller(@Nullable Throwable cause) {
        return new ServerException(ServerErrorCode.INVALID_TASK_CALLER, "taskCaller.parse", null, cause);
    }
    @Override public boolean equals(Object other) {
        return other instanceof TaskCaller caller && Objects.equals(userId, caller.userId)
                && role.equals(caller.role) && Objects.equals(serviceProject, caller.serviceProject)
                && Objects.equals(access, caller.access) && projectIds.equals(caller.projectIds);
    }
    @Override public int hashCode() { return Objects.hash(userId, role, serviceProject, access, projectIds); }
    private static ServerException forbidden() {
        return new ServerException(ServerErrorCode.TASK_ACCESS_DENIED, "taskAccess.require", null, null);
    }
}
