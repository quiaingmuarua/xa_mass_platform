package com.xa.mass.testing.support;

import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Test support for the worker registration spine.
 *
 * <p>This class centralizes only the contract shape that must stay consistent
 * across proof runners: AdapterNode, NodeGroupBinding, then Worker. Scenario
 * topology, capability declarations, worker behavior, and assertions stay in
 * the owning runner.</p>
 */
public final class WorkerRegistrationSpineSupport {

    private WorkerRegistrationSpineSupport() {
    }

    public static void registerAdapterNode(MassSdkApplication app,
                                           String adapterNodeId,
                                           String adapterType) {
        Objects.requireNonNull(app, "app");
        app.registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId(adapterNodeId)
                .adapterType(adapterType)
                .endpointId(adapterNodeId)
                .build());
    }

    public static void bindNodeGroups(MassSdkApplication app,
                                      String adapterNodeId,
                                      Collection<String> workerGroupIds) {
        Objects.requireNonNull(workerGroupIds, "workerGroupIds");
        for (String workerGroupId : workerGroupIds) {
            bindNodeGroup(app, adapterNodeId, workerGroupId);
        }
    }

    public static void bindNodeGroup(MassSdkApplication app,
                                     String adapterNodeId,
                                     String workerGroupId) {
        Objects.requireNonNull(app, "app");
        app.bindNodeGroup(NodeGroupBindingRegistration.builder()
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .build());
    }

    public static void registerWorker(MassSdkApplication app,
                                      String workerId,
                                      String adapterNodeId,
                                      String workerGroupId,
                                      String transportHint,
                                      String adapterId,
                                      int maxConcurrentWork,
                                      Map<String, String> attributes) {
        Objects.requireNonNull(app, "app");
        app.registerWorker(WorkerRegistration.builder()
                .workerId(workerId)
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .transportHint(transportHint)
                .adapterId(adapterId)
                .maxConcurrentWork(maxConcurrentWork)
                .attributes(attributes)
                .build());
    }
}
