package com.xa.mass.server.bootstrap;

import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.storage.rule.RuleDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevDemoBootstrapDataProviderTest {

    @Test
    void loadIntoGeneratesWorkersProjectsAndLifecycleMix() {
        DevDemoBootstrapDataProvider provider = new DevDemoBootstrapDataProvider(
                4,
                12,
                3,
                20,
                1,
                true,
                List.of("us", "gb")
        );
        RecordingRuntime runtime = new RecordingRuntime();

        provider.loadInto(runtime);

        assertEquals(4, runtime.workers.size());
        assertEquals(4, runtime.workerContexts.size());
        assertEquals(12, runtime.createdTasks.size());
        assertEquals(12, runtime.appendRequests.size());
        assertEquals(12, runtime.sealedTaskIds.size());

        Set<String> projects = runtime.createdTasks.stream()
                .map(MassTaskShellCreateRequest::getProject)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("demoApp", "demoOps"), projects);

        assertIterableEquals(List.of("task-1", "task-2", "task-3", "task-4", "task-5", "task-6", "task-9", "task-10"),
                runtime.approvedTaskIds);
        assertIterableEquals(List.of("task-9", "task-10"), runtime.pausedTaskIds);
        assertIterableEquals(List.of("task-11", "task-12"), runtime.rejectedTaskIds);

        assertEquals("demo.dispatch", runtime.appendRequests.get(0).getEventCode());
        assertEquals("demo.dispatch.gb", runtime.appendRequests.get(1).getEventCode());
        assertEquals("demo.dispatch.gb", runtime.appendRequests.get(2).getEventCode());
        assertEquals("demo.dispatch.gb", runtime.appendRequests.get(3).getEventCode());

        Map<String, Object> firstSharedConfig = runtime.createdTasks.get(0).getSharedConfig();
        assertEquals("active", firstSharedConfig.get("demoScenario"));
        assertEquals("demoApp", firstSharedConfig.get("demoProject"));
        assertEquals("demo-app-submitter",
                TaskOwnershipStamp.fromSharedConfig(firstSharedConfig).getCreatedByPrincipalId());

        Map<String, Object> secondSharedConfig = runtime.createdTasks.get(1).getSharedConfig();
        assertEquals("demo-ops-submitter",
                TaskOwnershipStamp.fromSharedConfig(secondSharedConfig).getCreatedByPrincipalId());

        WorkerRegistration firstWorker = runtime.workers.get(0);
        assertTrue(firstWorker.getEventBindings().stream()
                .allMatch(binding -> binding.getProjectCodes().containsAll(List.of("demoApp", "demoOps"))));

        WorkerContextRegistration firstContext = runtime.workerContexts.get(0);
        assertEquals(Set.of("us"), firstContext.getRoutingTags());
    }

    private static final class RecordingRuntime implements MassRuntimeControl {

        private final List<WorkerRegistration> workers = new ArrayList<>();
        private final List<WorkerContextRegistration> workerContexts = new ArrayList<>();
        private final List<MassTaskShellCreateRequest> createdTasks = new ArrayList<>();
        private final List<MassTaskItemBatchAppendRequest> appendRequests = new ArrayList<>();
        private final List<String> approvedTaskIds = new ArrayList<>();
        private final List<String> pausedTaskIds = new ArrayList<>();
        private final List<String> rejectedTaskIds = new ArrayList<>();
        private final List<String> sealedTaskIds = new ArrayList<>();
        private int nextTaskId = 1;

        @Override
        public EventResponse dispatchEvent(EventRequest request, PrincipalContext principal) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskShellSnapshot createTaskShell(MassTaskShellCreateRequest request) {
            createdTasks.add(request);
            String taskId = "task-" + nextTaskId++;
            return new TaskShellSnapshot(taskId, taskId, null, request.getProject(), request.getUserId(), request.getContract(), request.getSourceRef());
        }

        @Override
        public boolean approveTask(String taskId) {
            approvedTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean rejectTask(String taskId) {
            rejectedTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean blockTask(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean pauseTask(String taskId) {
            pausedTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancelTask(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean terminateTask(String taskId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int appendTaskItems(String taskId, MassTaskItemBatchAppendRequest request) {
            appendRequests.add(request);
            return request.getItems().size();
        }

        @Override
        public boolean sealTask(String taskId) {
            sealedTaskIds.add(taskId);
            return true;
        }

        @Override
        public void registerWorker(WorkerRegistration request) {
            workers.add(request);
        }

        @Override
        public void registerWorkerContext(WorkerContextRegistration request) {
            workerContexts.add(request);
        }

        @Override
        public void replaceDefaultRules(Collection<RuleDefinition> rules) {
            throw new UnsupportedOperationException();
        }
    }
}
