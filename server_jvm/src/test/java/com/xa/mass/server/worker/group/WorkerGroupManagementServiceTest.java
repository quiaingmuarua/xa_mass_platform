package com.xa.mass.server.worker.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.NonTerminalTaskPage;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.GroupStateSummary;
import com.xa.mass.kernel.score.WorkerScoreCore.InactiveWorkerBatch;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerRemovalResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerRemovalStatus;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationResult;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupPage;
import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties;
import com.xa.mass.server.assembly.runtime.StartupSeededWorkerGroups;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectAssemblyProperties;
import com.xa.mass.server.project.ProjectDefinition;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.testing.TaskCallers;
import com.xa.mass.server.worker.connection.WorkerConnectionControlStore;
import com.xa.mass.server.worker.identity.WorkerIdentityService;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.workermatching.MatchingGroup;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class WorkerGroupManagementServiceTest {

    private static final com.xa.mass.server.user.UserDirectory TEST_USERS = TaskCallers.directory(
            new com.xa.mass.server.user.UserDirectory.User("admin1", "Admin", "admin", List.of()),
            new com.xa.mass.server.user.UserDirectory.User("oper1", "Operator", "operator", List.of())
    );
    private static final TaskCaller ADMIN = TEST_USERS.authenticate(TaskCallers.devKey("admin1"));
    private static final TaskCaller OPERATOR = TEST_USERS.authenticate(TaskCallers.devKey("oper1"));

    private WorkerResourceCatalog workerCatalog;
    private WorkerScoreCore workerScoreCore;
    private TaskScoreBandCore taskScoreCore;
    private TaskResourceCatalog taskCatalog;
    private WorkerMatchingCatalog matchingCatalog;
    private WorkerIdentityService identityService;
    private WorkerConnectionControlStore connectionControls;
    private ProjectDirectory projects;
    private WorkerGroupBindings bindings;
    private WorkerGroupManagementService service;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        workerScoreCore = mock(WorkerScoreCore.class);
        taskScoreCore = mock(TaskScoreBandCore.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        matchingCatalog = mock(WorkerMatchingCatalog.class);
        identityService = mock(WorkerIdentityService.class);
        connectionControls = mock(WorkerConnectionControlStore.class);
        projects = new ProjectDirectory(
                new ProjectAssemblyProperties(List.of(
                        new ProjectDefinition("proj-declared", List.of("seeded-group", "bound-group"), null),
                        new ProjectDefinition("default", List.of(), null)
                )),
                List.of()
        );
        bindings = new WorkerGroupBindings(
                Map.of("bound-group", new MatchingGroup(Set.of(), Set.of("worker.phone"))),
                new FixedWindowBindingsProperties(Map.of(), Map.of()),
                List.of()
        );
        service = new WorkerGroupManagementService(
                workerCatalog,
                workerScoreCore,
                taskScoreCore,
                taskCatalog,
                matchingCatalog,
                identityService,
                connectionControls,
                projects,
                bindings,
                new StartupSeededWorkerGroups(Set.of("seeded-group")),
                new OperationGuard()
        );
    }

    @Test
    void nonAdminCallerIsRejectedForAllManagementOperations() {
        assertThatThrownBy(() -> service.list(OPERATOR, "0", 10))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED);
        assertThatThrownBy(() -> service.update(OPERATOR, "dyn", Map.of(), List.of()))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED);
        assertThatThrownBy(() -> service.deleteGroup(OPERATOR, "dyn"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED);
        assertThatThrownBy(() -> service.deleteOfflineWorker(OPERATOR, "dyn", "w-1"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.TASK_ACCESS_DENIED);
    }

    @Test
    void listReportsProtectionFlagsDeclaredProjectsAndKernelCounts() {
        var groups = new LinkedHashMap<String, WorkerGroupDescriptor>();
        groups.put("seeded-group", new WorkerGroupDescriptor("seeded-group", Map.of("tier", "core"), Set.of("ev.a")));
        groups.put("bound-group", new WorkerGroupDescriptor("bound-group", Map.of(), Set.of("ev.b")));
        groups.put("dynamic-group", new WorkerGroupDescriptor("dynamic-group", Map.of("env", "test"), Set.of("ev.c")));
        groups.put("corrupt-group", null);
        when(workerCatalog.scanWorkerGroupDescriptors("0", 50)).thenReturn(new WorkerGroupPage("42", groups));
        when(workerScoreCore.summarizeGroupStates("seeded-group"))
                .thenReturn(new GroupStateSummary(5, 2, 1, 0, 2, 0));
        when(workerScoreCore.summarizeGroupStates("bound-group"))
                .thenReturn(new GroupStateSummary(1, 0, 0, 1, 0, 0));
        when(workerScoreCore.summarizeGroupStates("dynamic-group"))
                .thenReturn(new GroupStateSummary(3, 0, 0, 0, 2, 1));

        var page = service.list(ADMIN, "0", 50);

        assertThat(page.nextCursor()).isEqualTo("42");
        assertThat(page.unreadableCount()).isEqualTo(1);
        assertThat(page.groups()).extracting("workerGroupId")
                .containsExactly("bound-group", "dynamic-group", "seeded-group");

        var bound = page.groups().get(0);
        assertThat(bound.source()).isEqualTo("startup_bound");
        assertThat(bound.editable()).isTrue();
        assertThat(bound.deletableByConfig()).isTrue();
        assertThat(bound.protectionReasons()).containsExactly("project:proj-declared", "matching");
        assertThat(bound.declaredProjects()).containsExactly("proj-declared");
        assertThat(bound.workerCounts().activeTotal()).isEqualTo(1);

        var dynamic = page.groups().get(1);
        assertThat(dynamic.source()).isEqualTo("dynamic");
        assertThat(dynamic.editable()).isTrue();
        assertThat(dynamic.deletableByConfig()).isTrue();
        assertThat(dynamic.protectionReasons()).isEmpty();
        assertThat(dynamic.workerCounts().total()).isEqualTo(3);
        assertThat(dynamic.workerCounts().cold()).isEqualTo(2);
        assertThat(dynamic.workerCounts().pausedOffline()).isEqualTo(1);
        assertThat(dynamic.workerCounts().activeTotal()).isEqualTo(0);

        var seeded = page.groups().get(2);
        assertThat(seeded.source()).isEqualTo("startup_seeded");
        assertThat(seeded.editable()).isTrue();
        assertThat(seeded.deletableByConfig()).isTrue();
        assertThat(seeded.protectionReasons()).containsExactly("host_seed", "project:proj-declared");
    }

    @Test
    void updateAllowsAnyRegisteredGroupIncludingStartupSeeded() {
        when(workerCatalog.updateWorkerGroup(new WorkerGroupDescriptor("seeded-group", Map.of("a", 1), Set.of("ev.a"))))
                .thenReturn(new RegistrationResult(RegistrationStatus.OK));
        assertThat(service.update(ADMIN, "seeded-group", Map.of("a", 1), List.of("ev.a")).status())
                .isEqualTo("updated");

        when(workerCatalog.updateWorkerGroup(new WorkerGroupDescriptor("dynamic-group", Map.of("k", "v"), Set.of("ev.x"))))
                .thenReturn(new RegistrationResult(RegistrationStatus.OK));
        var updated = service.update(ADMIN, "dynamic-group", Map.of("k", "v"), List.of("ev.x"));
        assertThat(updated.status()).isEqualTo("updated");

        when(workerCatalog.updateWorkerGroup(new WorkerGroupDescriptor("bound-group", Map.of(), Set.of("ev.b"))))
                .thenReturn(new RegistrationResult(RegistrationStatus.NOOP));
        assertThat(service.update(ADMIN, "bound-group", Map.of(), List.of("ev.b")).status()).isEqualTo("unchanged");

        when(workerCatalog.updateWorkerGroup(new WorkerGroupDescriptor("missing", Map.of(), Set.of())))
                .thenReturn(new RegistrationResult(RegistrationStatus.NOT_FOUND, "missing"));
        assertThatThrownBy(() -> service.update(ADMIN, "missing", Map.of(), List.of()))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_GROUP_NOT_FOUND);
    }

    @Test
    void deleteGroupBlocksNonTerminalTaskAndActiveWorkersAndAllowsInactiveSeededGroup() {
        when(taskScoreCore.scanNonTerminalTasks("0", 200)).thenReturn(new NonTerminalTaskPage(
                List.of(new TaskScoreState("task-1", 100L, TaskScoreBand.RUNNING_VISIBLE, 10_000L, 1)),
                "0",
                1
        ));
        when(taskCatalog.loadTaskAllocationDescriptors(List.of("task-1"))).thenReturn(Map.of(
                "task-1",
                new TaskDescriptor(
                        "task-1",
                        "dynamic-group",
                        TaskIdleDisposition.CLOSE_WHEN_IDLE,
                        Map.of("priority", "1", "maxRetryTimes", "3"),
                        List.of(),
                        null,
                        Map.of()
                )
        ));
        assertThatThrownBy(() -> service.deleteGroup(ADMIN, "dynamic-group"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT);

        when(taskScoreCore.scanNonTerminalTasks("0", 200)).thenReturn(new NonTerminalTaskPage(List.of(), "0", 0));
        when(workerScoreCore.summarizeGroupStates("dynamic-group"))
                .thenReturn(new GroupStateSummary(2, 1, 0, 0, 1, 0));
        assertThatThrownBy(() -> service.deleteGroup(ADMIN, "dynamic-group"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT);
        verify(workerCatalog, never()).deleteWorkerGroup(anyString());

        when(workerScoreCore.summarizeGroupStates("seeded-group"))
                .thenReturn(new GroupStateSummary(0, 0, 0, 0, 0, 0));
        when(workerCatalog.getWorkerGroupDescriptors(List.of("seeded-group")))
                .thenReturn(Map.of("seeded-group", new WorkerGroupDescriptor("seeded-group", Map.of(), Set.of())));
        when(workerScoreCore.peekInactiveWorkerIds("seeded-group", 100))
                .thenReturn(new InactiveWorkerBatch(List.of(), false));
        assertThat(service.deleteGroup(ADMIN, "seeded-group").status()).isEqualTo("deleted");
    }

    @Test
    void deleteGroupFailsClosedWhenNonTerminalTaskScanExceedsBoundedPages() {
        when(taskScoreCore.scanNonTerminalTasks(anyString(), anyInt()))
                .thenReturn(new NonTerminalTaskPage(List.of(), "999", 1));
        assertThatThrownBy(() -> service.deleteGroup(ADMIN, "dynamic-group"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_UNAVAILABLE);
        verify(workerCatalog, never()).deleteWorkerGroup(anyString());
    }

    @Test
    void deleteGroupFencesCatalogDrainsBindingsBeforeScoresAndPurgesOwners() {
        when(taskScoreCore.scanNonTerminalTasks("0", 200)).thenReturn(new NonTerminalTaskPage(List.of(), "0", 0));
        when(workerScoreCore.summarizeGroupStates("dynamic-group"))
                .thenReturn(new GroupStateSummary(2, 0, 0, 0, 1, 1));
        when(workerCatalog.getWorkerGroupDescriptors(List.of("dynamic-group")))
                .thenReturn(Map.of("dynamic-group", new WorkerGroupDescriptor("dynamic-group", Map.of(), Set.of())));
        when(workerScoreCore.peekInactiveWorkerIds("dynamic-group", 100))
                .thenReturn(new InactiveWorkerBatch(List.of("w-1", "w-2"), false))
                .thenReturn(new InactiveWorkerBatch(List.of(), false));
        when(workerScoreCore.removeInactiveWorkers("dynamic-group", List.of("w-1", "w-2")))
                .thenReturn(new WorkerRemovalResult(WorkerRemovalStatus.REMOVED, 2, 0));

        var response = service.deleteGroup(ADMIN, "dynamic-group");
        assertThat(response.workerGroupId()).isEqualTo("dynamic-group");
        assertThat(response.status()).isEqualTo("deleted");
        assertThat(response.removedWorkers()).isEqualTo(2);

        InOrder order = Mockito.inOrder(
                workerCatalog, workerScoreCore, identityService, connectionControls, matchingCatalog
        );
        order.verify(workerCatalog).deleteWorkerGroup("dynamic-group");
        order.verify(workerCatalog).removeWorkerBindings("dynamic-group", List.of("w-1", "w-2"));
        order.verify(workerScoreCore).removeInactiveWorkers("dynamic-group", List.of("w-1", "w-2"));
        order.verify(identityService).deleteGroup("dynamic-group");
        order.verify(connectionControls).deleteGroup("dynamic-group");
        order.verify(matchingCatalog).purgeWorkerGroup("dynamic-group");
    }

    @Test
    void deleteGroupAllowsIdempotentRetryWhenCatalogEntryWasAlreadyRemoved() {
        when(taskScoreCore.scanNonTerminalTasks("0", 200)).thenReturn(new NonTerminalTaskPage(List.of(), "0", 0));
        when(workerScoreCore.summarizeGroupStates("dynamic-group"))
                .thenReturn(new GroupStateSummary(1, 0, 0, 0, 1, 0));
        when(workerCatalog.getWorkerGroupDescriptors(List.of("dynamic-group"))).thenReturn(Map.of());
        when(workerScoreCore.peekInactiveWorkerIds("dynamic-group", 100))
                .thenReturn(new InactiveWorkerBatch(List.of("w-leftover"), false))
                .thenReturn(new InactiveWorkerBatch(List.of(), false));
        when(workerScoreCore.removeInactiveWorkers("dynamic-group", List.of("w-leftover")))
                .thenReturn(new WorkerRemovalResult(WorkerRemovalStatus.REMOVED, 1, 0));

        var response = service.deleteGroup(ADMIN, "dynamic-group");
        assertThat(response.removedWorkers()).isEqualTo(1);
    }

    @Test
    void deleteOfflineWorkerVerifiesScoreFirstAndAllowsProtectedGroups() {
        when(workerCatalog.getWorkerDescriptors(List.of("w-active")))
                .thenReturn(Map.of("w-active", new WorkerDescriptor("w-active", "seeded-group", "ep-1")));
        when(workerScoreCore.removeInactiveWorkers("seeded-group", List.of("w-active")))
                .thenReturn(new WorkerRemovalResult(WorkerRemovalStatus.ACTIVE_CONFLICT, 0, 0));

        assertThatThrownBy(() -> service.deleteOfflineWorker(ADMIN, "seeded-group", "w-active"))
                .isInstanceOf(ServerException.class)
                .extracting("errorCode").isEqualTo(ServerErrorCode.WORKER_RESOURCE_STATE_CONFLICT);
        verify(workerCatalog, never()).removeWorkerBindings(anyString(), anyList());
        verify(matchingCatalog, never()).purgeWorker(anyString(), anyString());

        when(workerCatalog.getWorkerDescriptors(List.of("w-cold")))
                .thenReturn(Map.of("w-cold", new WorkerDescriptor("w-cold", "seeded-group", "ep-1")));
        when(workerScoreCore.removeInactiveWorkers("seeded-group", List.of("w-cold")))
                .thenReturn(new WorkerRemovalResult(WorkerRemovalStatus.REMOVED, 1, 0));

        var deleted = service.deleteOfflineWorker(ADMIN, "seeded-group", "w-cold");
        assertThat(deleted.workerGroupId()).isEqualTo("seeded-group");
        assertThat(deleted.workerId()).isEqualTo("w-cold");
        assertThat(deleted.status()).isEqualTo("deleted");

        InOrder order = Mockito.inOrder(workerScoreCore, workerCatalog, connectionControls, matchingCatalog);
        order.verify(workerScoreCore).removeInactiveWorkers("seeded-group", List.of("w-cold"));
        order.verify(workerCatalog).removeWorkerBindings("seeded-group", List.of("w-cold"));
        order.verify(connectionControls).deleteWorker("seeded-group", "w-cold");
        order.verify(matchingCatalog).purgeWorker("seeded-group", "w-cold");
        verify(identityService, never()).deleteGroup(anyString());
    }
}
