package com.xa.mass.engine.testutil;

import com.xa.mass.base.model.Worker;
import com.xa.mass.worker.runtime.resource.AdapterNodeRecord;
import com.xa.mass.worker.runtime.resource.EventBinding;
import com.xa.mass.worker.runtime.resource.NodeGroupBindingRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.worker.runtime.WorkerManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test-only support for registering workers through the current group-first spine.
 */
public final class WorkerRegistrationTestSupport {

    private static final String DEFAULT_PROJECT = "demoApp";
    private static final String ADAPTER_NODE_PREFIX = "test-node-";

    private WorkerRegistrationTestSupport() {
    }

    public static Worker registerWorker(WorkerManager workerManager, Worker worker) {
        String groupId = normalizeGroupId(worker.getWorkerGroupId());
        ensureWorkerGroup(workerManager, groupId, worker.getSupportedProjects(), worker.getSupportedEventCodes());
        String adapterNodeId = adapterNodeIdForGroup(groupId);
        workerManager.registerAdapterNode(adapterNode(adapterNodeId));
        workerManager.bindNodeGroup(binding(adapterNodeId, groupId));
        worker.setWorkerGroupId(groupId);
        worker.setAdapterNodeId(adapterNodeId);
        if (worker.getLastHeartbeat() == null) {
            worker.setLastHeartbeat(LocalDateTime.now());
        }
        if (worker.getOnlineStrategy() == null || worker.getOnlineStrategy().isBlank()) {
            worker.setOnlineStrategy("polling");
        }
        workerManager.addWorker(workerDeclaration(worker));
        refreshHeartbeatEvidence(workerManager, worker);
        return worker;
    }

    private static WorkerDeclarationRecord workerDeclaration(Worker worker) {
        return new WorkerDeclarationRecord(
                worker.getWorkerId(),
                worker.getWorkerGroupId(),
                worker.getOnlineStrategy(),
                worker.getAgentVersion(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes()
        );
    }

    private static void refreshHeartbeatEvidence(WorkerManager workerManager, Worker worker) {
        long observedAtMillis = worker.getLastHeartbeat()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        workerManager.refreshWorkerHeartbeat(worker.getWorkerId(), observedAtMillis);
    }

    public static void ensureWorkerRegistrationSpine(WorkerManager workerManager,
                                                     String groupId,
                                                     List<String> projectCodes,
                                                     List<String> eventCodes) {
        String normalizedGroupId = normalizeGroupId(groupId);
        ensureWorkerGroup(workerManager, normalizedGroupId, projectCodes, eventCodes);
        String adapterNodeId = adapterNodeIdForGroup(normalizedGroupId);
        workerManager.registerAdapterNode(adapterNode(adapterNodeId));
        workerManager.bindNodeGroup(binding(adapterNodeId, normalizedGroupId));
    }

    private static void ensureWorkerGroup(WorkerManager workerManager,
                                          String groupId,
                                          List<String> projectCodes,
                                          List<String> eventCodes) {
        if (workerManager.workerGroup(groupId).isPresent()) {
            return;
        }
        Set<String> projects = normalizeProjects(projectCodes);
        WorkerGroupRecord.Builder builder = WorkerGroupRecord.builder(groupId)
                .projectCodes(projects);
        Set<EventBinding> bindings = eventBindings(eventCodes, projects);
        if (!bindings.isEmpty()) {
            builder.eventBindings(bindings);
        }
        workerManager.upsertWorkerGroup(builder.build());
    }

    private static Set<EventBinding> eventBindings(List<String> eventCodes, Set<String> projects) {
        if (eventCodes == null || eventCodes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<EventBinding> bindings = new LinkedHashSet<>();
        for (String eventCode : eventCodes) {
            if (eventCode != null && !eventCode.isBlank()) {
                bindings.add(EventBinding.of(eventCode, projects));
            }
        }
        return bindings;
    }

    private static Set<String> normalizeProjects(List<String> projectCodes) {
        LinkedHashSet<String> projects = new LinkedHashSet<>();
        if (projectCodes != null) {
            for (String projectCode : projectCodes) {
                if (projectCode != null && !projectCode.isBlank()) {
                    projects.add(projectCode.trim());
                }
            }
        }
        if (projects.isEmpty()) {
            projects.add(DEFAULT_PROJECT);
        }
        return projects;
    }

    private static String normalizeGroupId(String groupId) {
        return groupId == null || groupId.isBlank() ? "test-worker-group" : groupId.trim();
    }

    private static String adapterNodeIdForGroup(String groupId) {
        return ADAPTER_NODE_PREFIX + groupId;
    }

    private static AdapterNodeRecord adapterNode(String adapterNodeId) {
        return new AdapterNodeRecord(
                adapterNodeId,
                "test",
                "1.0.0",
                "endpoint-" + adapterNodeId,
                true,
                true,
                null,
                null,
                Map.of()
        );
    }

    private static NodeGroupBindingRecord binding(String adapterNodeId, String groupId) {
        return new NodeGroupBindingRecord(
                adapterNodeId,
                groupId,
                "test-plugin",
                "test-deployment",
                true,
                false,
                null,
                null,
                Map.of()
        );
    }
}
