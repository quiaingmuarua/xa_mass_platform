package com.xa.mass.server.bootstrap;

import com.xa.mass.sdk.MassRuntimeControl;
import com.xa.mass.sdk.WorkerClientOperations;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.authz.TaskOwnershipStamp;
import com.xa.mass.sdk.event.EventRequest;
import com.xa.mass.sdk.event.EventResponse;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.MassTaskItemBatchAppendRequest;
import com.xa.mass.sdk.model.MassTaskShellCreateRequest;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.TaskShellSnapshot;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
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

class ControlConsoleScenarioBootstrapDataProviderTest {

    @Test
    void loadIntoGeneratesProbeWorkersProjectsAndStoppedTasks() {
        ControlConsoleScenarioBootstrapDataProvider provider = new ControlConsoleScenarioBootstrapDataProvider(
                ControlConsoleScenarioBootstrapDataProvider.PROFILE_LOCAL_ONLY,
                100,
                10,
                120,
                20,
                1,
                false
        );
        RecordingRuntime runtime = new RecordingRuntime();

        provider.loadInto(runtime);

        assertTrue(runtime.workers.size() >= 100);
        assertEquals(10, runtime.createdTasks.size());
        assertEquals(10, runtime.appendRequests.size());
        assertEquals(10, runtime.sealedTaskIds.size());
        assertEquals(1200, runtime.appendRequests.stream().mapToInt(request -> request.getItems().size()).sum());

        Set<String> projects = runtime.createdTasks.stream()
                .map(MassTaskShellCreateRequest::getProject)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("publicProbe", "deviceProbe", "dataQualityProbe"), projects);

        assertTrue(runtime.approvedTaskIds.isEmpty(), "default scenario tasks must not auto-run");
        assertTrue(runtime.pausedTaskIds.isEmpty(), "default scenario should not create hidden active lifecycle");
        assertTrue(runtime.rejectedTaskIds.isEmpty(), "default scenario should not reject generated tasks");

        Set<String> eventCodes = runtime.appendRequests.stream()
                .map(MassTaskItemBatchAppendRequest::getEventCode)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(eventCodes.containsAll(List.of(
                "probe.url.dns",
                "probe.phone.metadata",
                "probe.csv.validate",
                "probe.json.schema"
        )));
        assertTrue(eventCodes.stream().noneMatch(event -> event.startsWith("demo.")));

        Map<String, Object> firstSharedConfig = runtime.createdTasks.get(0).getSharedConfig();
        assertEquals("control-console-realistic", firstSharedConfig.get("scenario"));
        assertEquals("local-only", firstSharedConfig.get("scenarioProfile"));
        assertEquals("public-probe-runner",
                TaskOwnershipStamp.fromSharedConfig(firstSharedConfig).getCreatedByPrincipalId());

        Map<String, Object> firstItem = (Map<String, Object>) runtime.appendRequests.get(0).getItems().get(0);
        assertTrue(firstItem.containsKey("sleepMs"));
        assertTrue(firstItem.containsKey("timeoutMs"));
        assertTrue(firstItem.containsKey("expectedOutcome"));
        assertTrue(firstItem.containsKey("traceLabel"));

        assertEquals(7, runtime.workerGroups.size());
        assertTrue(runtime.workerGroups.stream()
                .flatMap(group -> group.getEventBindings().stream())
                .noneMatch(binding -> binding.getEventCode().startsWith("demo.")));
        assertTrue(runtime.workers.stream().anyMatch(worker ->
                "polling".equals(worker.getTransportHint())));
        assertTrue(runtime.workers.stream().anyMatch(worker ->
                "realtime".equals(worker.getTransportHint())));
        assertTrue(runtime.workers.stream()
                .filter(worker -> "phone-device-probe".equals(worker.getWorkerGroupId()))
                .count() >= 30);
        assertTrue(runtime.workers.stream()
                .filter(worker -> "phone-device-probe".equals(worker.getWorkerGroupId()))
                .map(worker -> worker.getAttributes().get("fingerprintProfile"))
                .distinct()
                .count() >= 10);
        assertTrue(runtime.onlineWorkerIds.stream().anyMatch(workerId -> workerId.startsWith("public-probe-http-poll-")));
    }

    private static final class RecordingRuntime implements MassRuntimeControl, WorkerClientOperations {

        private final List<WorkerRegistration> workers = new ArrayList<>();
        private final List<WorkerGroupDeclaration> workerGroups = new ArrayList<>();
        private final List<MassTaskShellCreateRequest> createdTasks = new ArrayList<>();
        private final List<MassTaskItemBatchAppendRequest> appendRequests = new ArrayList<>();
        private final List<String> approvedTaskIds = new ArrayList<>();
        private final List<String> pausedTaskIds = new ArrayList<>();
        private final List<String> rejectedTaskIds = new ArrayList<>();
        private final List<String> sealedTaskIds = new ArrayList<>();
        private final List<String> onlineWorkerIds = new ArrayList<>();
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
        public com.xa.mass.sdk.model.TaskCommandResult executeTaskCommand(
                String taskId,
                com.xa.mass.sdk.model.MassTaskCommandRequest request) {
            String command = request == null || request.getCommand() == null
                    ? null
                    : request.getCommand().trim().toUpperCase(java.util.Locale.ROOT);
            if ("APPROVE".equals(command)) {
                approvedTaskIds.add(taskId);
                return commandResult(taskId, command, true);
            }
            if ("REJECT".equals(command)) {
                rejectedTaskIds.add(taskId);
                return commandResult(taskId, command, true);
            }
            if ("PAUSE".equals(command)) {
                pausedTaskIds.add(taskId);
                return commandResult(taskId, command, true);
            }
            if ("SEAL".equals(command)) {
                sealedTaskIds.add(taskId);
                return commandResult(taskId, command, true);
            }
            return commandResult(taskId, command, false);
        }

        @Override
        public boolean sealTask(String taskId) {
            sealedTaskIds.add(taskId);
            return true;
        }

        @Override
        public void registerAdapterNode(AdapterNodeRegistration request) {
        }

        @Override
        public void bindNodeGroup(NodeGroupBindingRegistration request) {
        }

        @Override
        public void declareWorkerGroup(WorkerGroupDeclaration request) {
            workerGroups.add(request);
        }

        @Override
        public void registerWorker(WorkerRegistration request) {
            workers.add(request);
        }

        @Override
        public String getWorkerAdapterId(String workerId) {
            return "polling";
        }

        @Override
        public String getWorkerTransportHint(String workerId) {
            return "polling";
        }

        @Override
        public com.xa.mass.sdk.worker.PullWorkerSession pullWorker(String workerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void workerOnline(String workerId, String reason) {
            onlineWorkerIds.add(workerId);
        }

        @Override
        public void workerHeartbeat(String workerId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void workerOffline(String workerId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.xa.mass.transport.channel.TaskPullResult pollTasksResult(String workerId,
                                                                            int maxMessages,
                                                                            long timeoutMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean submitResult(String workerId, com.xa.mass.transport.model.TaskResultReport report) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replaceDefaultRules(Collection<RuleDefinition> rules) {
            throw new UnsupportedOperationException();
        }

        private com.xa.mass.sdk.model.TaskCommandResult commandResult(String taskId, String command, boolean accepted) {
            return new com.xa.mass.sdk.model.TaskCommandResult(
                    taskId,
                    command,
                    accepted,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}
