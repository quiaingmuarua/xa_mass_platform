package com.xa.mass.testing.support;

import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;

import java.util.Collection;
import java.util.Objects;

/**
 * Test support for the worker registration spine.
 *
 * <p>This class centralizes only AdapterNode / NodeGroupBinding setup. Worker
 * registration stays in the runner through the SDK builder so field names remain
 * visible at each scenario call site.</p>
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
}
