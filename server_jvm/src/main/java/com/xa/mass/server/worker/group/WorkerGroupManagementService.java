package com.xa.mass.server.worker.group;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.GroupStateSummary;
import com.xa.mass.kernel.score.WorkerScoreCore.InactiveWorkerBatch;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerRemovalResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerRemovalStatus;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationResult;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupPage;
import com.xa.mass.server.api.v1.contract.worker.WorkerDeleteResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupDeleteResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupDirectoryPageResponse;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupDirectoryPageResponse.WorkerGroupManagedView;
import com.xa.mass.server.api.v1.contract.worker.WorkerGroupDirectoryPageResponse.WorkerGroupWorkerCounts;
import com.xa.mass.server.assembly.runtime.StartupSeededWorkerGroups;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationAlreadyRunningException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.worker.connection.WorkerConnectionControlStore;
import com.xa.mass.server.worker.identity.WorkerIdentityService;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Admin-owned WorkerGroup lifecycle management and single offline Worker cleanup.
 *
 * <p>Coordinates bounded Kernel, Matching and Server Runtime keys without modifying
 * SQLite Task history or bypassing startup configuration protections.</p>
 */
@Service
public final class WorkerGroupManagementService {

    private static final int MAX_PAGE_LIMIT = 100;
    private static final int TASK_SCAN_PAGE_SIZE = 200;
    private static final int MAX_TASK_SCAN_PAGES = 20;
    private static final int WORKER_DRAIN_BATCH_SIZE = 100;
    private static final int MAX_WORKER_DRAIN_BATCHES = 500;

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerScoreCore workerScoreCore;
    private final TaskScoreBandCore taskScoreCore;
    private final TaskResourceCatalog taskCatalog;
    private final WorkerMatchingCatalog matchingCatalog;
    private final WorkerIdentityService identityService;
    private final WorkerConnectionControlStore connectionControls;
    private final ProjectDirectory projects;
    private final WorkerGroupBindings bindings;
    private final StartupSeededWorkerGroups seededGroups;
    private final OperationGuard operations;

    @Autowired
    public WorkerGroupManagementService(
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScoreCore,
            TaskScoreBandCore taskScoreCore,
            TaskResourceCatalog taskCatalog,
            WorkerMatchingCatalog matchingCatalog,
            WorkerIdentityService identityService,
            WorkerConnectionControlStore connectionControls,
            ProjectDirectory projects,
            WorkerGroupBindings bindings,
            @Nullable StartupSeededWorkerGroups seededGroups,
            OperationGuard operations
    ) {
        this.workerCatalog = Objects.requireNonNull(workerCatalog, "workerCatalog");
        this.workerScoreCore = Objects.requireNonNull(workerScoreCore, "workerScoreCore");
        this.taskScoreCore = Objects.requireNonNull(taskScoreCore, "taskScoreCore");
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.matchingCatalog = Objects.requireNonNull(matchingCatalog, "matchingCatalog");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.connectionControls = Objects.requireNonNull(connectionControls, "connectionControls");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.seededGroups = seededGroups == null ? StartupSeededWorkerGroups.empty() : seededGroups;
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public WorkerGroupDirectoryPageResponse list(TaskCaller caller, @Nullable String cursor, int limit) {
        caller.requireAdmin();
        String normalizedCursor = (cursor == null || cursor.isBlank()) ? "0" : cursor.strip();
        if (!normalizedCursor.matches("[0-9]+") || limit < 1 || limit > MAX_PAGE_LIMIT) {
            throw new ServerException(
                    ServerErrorCode.INVALID_WORKER_GROUP_REQUEST,
                    "workerGroup.list",
                    "cursor must be numeric and limit must be in 1.." + MAX_PAGE_LIMIT,
                    null
            );
        }
        try {
            WorkerGroupPage page = workerCatalog.scanWorkerGroupDescriptors(normalizedCursor, limit);
            int unreadable = 0;
            var views = new ArrayList<WorkerGroupManagedView>(page.groups().size());
            for (var entry : page.groups().entrySet()) {
                WorkerGroupDescriptor descriptor = entry.getValue();
                if (descriptor == null) {
                    unreadable++;
                    continue;
                }
                String groupId = descriptor.workerGroupId();
                ProtectionInfo protection = inspectProtection(groupId);
                GroupStateSummary summary = workerScoreCore.summarizeGroupStates(groupId);
                views.add(new WorkerGroupManagedView(
                        groupId,
                        descriptor.attributes(),
                        descriptor.eventCodes().stream().sorted().toList(),
                        protection.source(),
                        protection.editable(),
                        protection.deletableByConfig(),
                        protection.reasons(),
                        protection.declaredProjects(),
                        new WorkerGroupWorkerCounts(
                                summary.total(),
                                summary.activeHot(),
                                summary.pausedActive(),
                                summary.recovery(),
                                summary.cold(),
                                summary.pausedOffline(),
                                summary.activeTotal()
                        )
                ));
            }
            views.sort(Comparator.comparing(WorkerGroupManagedView::workerGroupId));
            return new WorkerGroupDirectoryPageResponse(page.nextCursor(), unreadable, views, Instant.now());
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE,
                    "workerGroup.list",
                    null,
                    error
            );
        }
    }

    public WorkerGroupRegistrationService.Registration update(
            TaskCaller caller,
            String workerGroupId,
            Map<String, Object> attributes,
            List<String> eventCodes
    ) {
        caller.requireAdmin();
        validateDescriptorInputs(workerGroupId, attributes, eventCodes, "workerGroup.update");
        try {
            return operations.execute("worker-group-manage", workerGroupId, () -> {
                RegistrationResult result = workerCatalog.updateWorkerGroup(
                        new WorkerGroupDescriptor(workerGroupId, attributes, new LinkedHashSet<>(eventCodes))
                );
                if (result == null) {
                    throw new ServerException(
                            ServerErrorCode.WORKER_GROUP_REGISTRATION_UNAVAILABLE,
                            "workerGroup.update",
                            null,
                            null
                    );
                }
                String status = switch (result.status()) {
                    case OK -> "updated";
                    case NOOP -> "unchanged";
                    case NOT_FOUND -> throw new ServerException(
                            ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                            "workerGroup.update",
                            result.reason(),
                            null
                    );
                    case INVALID -> throw new ServerException(
                            ServerErrorCode.INVALID_WORKER_GROUP_REQUEST,
                            "workerGroup.update",
                            result.reason(),
                            null
                    );
                    case CONFLICT -> throw new ServerException(
                            ServerErrorCode.WORKER_GROUP_REGISTRATION_CONFLICT,
                            "workerGroup.update",
                            result.reason(),
                            null
                    );
                };
                return new WorkerGroupRegistrationService.Registration(workerGroupId, status);
            });
        } catch (OperationAlreadyRunningException busy) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                    "workerGroup.update",
                    "Another management operation is already running for this WorkerGroup",
                    busy
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.WORKER_GROUP_REGISTRATION_UNAVAILABLE,
                    "workerGroup.update",
                    null,
                    error
            );
        }
    }

    public WorkerGroupDeleteResponse deleteGroup(TaskCaller caller, String workerGroupId) {
        caller.requireAdmin();
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw new ServerException(
                    ServerErrorCode.INVALID_WORKER_GROUP_REQUEST,
                    "workerGroup.delete",
                    "workerGroupId must be non-blank",
                    null
            );
        }
        try {
            return operations.execute("worker-group-manage", workerGroupId, () -> {
                List<String> activeTaskIds = findNonTerminalTasksForGroup(workerGroupId);
                if (!activeTaskIds.isEmpty()) {
                    String sample = String.join(", ", activeTaskIds.subList(0, Math.min(5, activeTaskIds.size())));
                    throw new ServerException(
                            ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                            "workerGroup.delete",
                            "WorkerGroup still has " + activeTaskIds.size()
                                    + " non-terminal or parked Task(s): " + sample,
                            null
                    );
                }
                GroupStateSummary summary = workerScoreCore.summarizeGroupStates(workerGroupId);
                if (summary.activeTotal() > 0) {
                    throw new ServerException(
                            ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                            "workerGroup.delete",
                            "WorkerGroup still has " + summary.activeTotal()
                                    + " active Worker(s); disconnect or migrate Workers first",
                            null
                    );
                }
                boolean descriptorPresent = workerCatalog.getWorkerGroupDescriptors(List.of(workerGroupId))
                        .get(workerGroupId) != null;
                if (!descriptorPresent && summary.total() == 0) {
                    throw new ServerException(
                            ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                            "workerGroup.delete",
                            "WorkerGroup was not found",
                            null
                    );
                }

                // Fence new registrations and Task creations before draining offline runtime state.
                workerCatalog.deleteWorkerGroup(workerGroupId);

                int removedWorkers = 0;
                for (int round = 0; round < MAX_WORKER_DRAIN_BATCHES; round++) {
                    InactiveWorkerBatch batch = workerScoreCore.peekInactiveWorkerIds(
                            workerGroupId, WORKER_DRAIN_BATCH_SIZE);
                    if (batch.activeConflict()) {
                        throw new ServerException(
                                ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                                "workerGroup.delete",
                                "An active Worker appeared while removing WorkerGroup; retry once offline",
                                null
                        );
                    }
                    if (batch.workerIds().isEmpty()) {
                        break;
                    }
                    workerCatalog.removeWorkerBindings(workerGroupId, batch.workerIds());
                    WorkerRemovalResult removal = workerScoreCore.removeInactiveWorkers(
                            workerGroupId, batch.workerIds());
                    if (removal.status() == WorkerRemovalStatus.ACTIVE_CONFLICT) {
                        throw new ServerException(
                                ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                                "workerGroup.delete",
                                "An active Worker appeared while removing WorkerGroup; retry once offline",
                                null
                        );
                    }
                    removedWorkers += removal.removedCount();
                    if (round == MAX_WORKER_DRAIN_BATCHES - 1
                            && workerScoreCore.summarizeGroupStates(workerGroupId).total() > 0) {
                        throw new ServerException(
                                ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE,
                                "workerGroup.delete",
                                "WorkerGroup exceeded per-request cleanup batch limit; retry DELETE to finish",
                                null
                        );
                    }
                }

                identityService.deleteGroup(workerGroupId);
                connectionControls.deleteGroup(workerGroupId);
                matchingCatalog.purgeWorkerGroup(workerGroupId);
                return new WorkerGroupDeleteResponse(workerGroupId, "deleted", removedWorkers);
            });
        } catch (OperationAlreadyRunningException busy) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                    "workerGroup.delete",
                    "Another management operation is already running for this WorkerGroup",
                    busy
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE,
                    "workerGroup.delete",
                    null,
                    error
            );
        }
    }

    public WorkerDeleteResponse deleteOfflineWorker(
            TaskCaller caller,
            String workerGroupId,
            String workerId
    ) {
        caller.requireAdmin();
        if (workerGroupId == null || workerGroupId.isBlank() || workerId == null || workerId.isBlank()) {
            throw new ServerException(
                    ServerErrorCode.INVALID_WORKER_RESOURCE_REQUEST,
                    "worker.delete",
                    "workerGroupId and workerId must be non-blank",
                    null
            );
        }
        try {
            return operations.execute("worker-manage", workerId, () -> {
                WorkerDescriptor descriptor = workerCatalog.getWorkerDescriptors(List.of(workerId)).get(workerId);
                if (descriptor == null || !workerGroupId.equals(descriptor.workerGroupId())) {
                    throw new ServerException(
                            ServerErrorCode.WORKER_RESOURCE_NOT_FOUND,
                            "worker.delete",
                            "Worker was not found in WorkerGroup",
                            null
                    );
                }
                // Check and remove Score atomically first so an active Worker is never half-mutated.
                WorkerRemovalResult removal = workerScoreCore.removeInactiveWorkers(
                        workerGroupId, List.of(workerId));
                if (removal.status() == WorkerRemovalStatus.ACTIVE_CONFLICT) {
                    throw new ServerException(
                            ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                            "worker.delete",
                            "Cannot remove an active Worker; disconnect it from the Worker controller first",
                            null
                    );
                }
                workerCatalog.removeWorkerBindings(workerGroupId, List.of(workerId));
                connectionControls.deleteWorker(workerGroupId, workerId);
                matchingCatalog.purgeWorker(workerGroupId, workerId);
                return new WorkerDeleteResponse(workerGroupId, workerId, "deleted");
            });
        } catch (OperationAlreadyRunningException busy) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT,
                    "worker.delete",
                    "Another management operation is already running for this Worker",
                    busy
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE,
                    "worker.delete",
                    null,
                    error
            );
        }
    }

    private ProtectionInfo inspectProtection(String workerGroupId) {
        var reasons = new ArrayList<String>();
        var declaredProjects = new ArrayList<String>();
        boolean seeded = seededGroups.contains(workerGroupId);
        if (seeded) {
            reasons.add("host_seed");
        }
        projects.projects().values().stream()
                .filter(p -> "declared".equals(p.workerGroupSource()) && p.workerGroupIds().contains(workerGroupId))
                .sorted(Comparator.comparing(ProjectDirectory.ProjectView::projectId))
                .forEach(p -> {
                    declaredProjects.add(p.projectId());
                    reasons.add("project:" + p.projectId());
                });
        if (bindings.matchingGroups().containsKey(workerGroupId)) {
            reasons.add("matching");
        }
        if (!bindings.group(workerGroupId).fixedWindowPools().isEmpty()) {
            reasons.add("fixed_window");
        }
        String source = seeded ? "startup_seeded" : (!reasons.isEmpty() ? "startup_bound" : "dynamic");
        return new ProtectionInfo(source, true, true, List.copyOf(reasons), List.copyOf(declaredProjects));
    }

    private List<String> findNonTerminalTasksForGroup(String workerGroupId) {
        var matched = new ArrayList<String>();
        String cursor = "0";
        for (int page = 0; page < MAX_TASK_SCAN_PAGES; page++) {
            var scanned = taskScoreCore.scanNonTerminalTasks(cursor, TASK_SCAN_PAGE_SIZE);
            var ids = scanned.tasks().stream().map(TaskScoreBandCore.TaskScoreState::taskId).toList();
            for (int offset = 0; offset < ids.size(); offset += 100) {
                var batch = ids.subList(offset, Math.min(offset + 100, ids.size()));
                var descriptors = taskCatalog.loadTaskAllocationDescriptors(batch);
                descriptors.forEach((taskId, descriptor) -> {
                    if (descriptor != null && workerGroupId.equals(descriptor.workerGroupId())) {
                        matched.add(taskId);
                    }
                });
            }
            if ("0".equals(scanned.nextCursor()) || scanned.scannedCount() == 0) {
                return matched;
            }
            cursor = scanned.nextCursor();
        }
        if (matched.isEmpty()) {
            throw new ServerException(
                    ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE,
                    "workerGroup.delete",
                    "Non-terminal task scan exceeded bounded page limit; cannot verify zero active tasks safely",
                    null
            );
        }
        return matched;
    }

    private static void validateDescriptorInputs(
            String workerGroupId,
            Map<String, Object> attributes,
            List<String> eventCodes,
            String operation
    ) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw new ServerException(ServerErrorCode.INVALID_WORKER_GROUP_REQUEST, operation, "workerGroupId must be non-blank", null);
        }
        if (attributes == null) {
            throw new ServerException(ServerErrorCode.INVALID_WORKER_GROUP_REQUEST, operation, "attributes must be present", null);
        }
        if (eventCodes == null) {
            throw new ServerException(ServerErrorCode.INVALID_WORKER_GROUP_REQUEST, operation, "eventCodes must be present", null);
        }
        var unique = new LinkedHashSet<String>();
        for (String eventCode : eventCodes) {
            if (eventCode == null || eventCode.isBlank()) {
                throw new ServerException(ServerErrorCode.INVALID_WORKER_GROUP_REQUEST, operation, "eventCodes must contain non-blank strings", null);
            }
            if (!unique.add(eventCode)) {
                throw new ServerException(ServerErrorCode.INVALID_WORKER_GROUP_REQUEST, operation, "eventCodes must be unique", null);
            }
        }
    }

    private record ProtectionInfo(
            String source,
            boolean editable,
            boolean deletableByConfig,
            List<String> reasons,
            List<String> declaredProjects
    ) {
    }
}
