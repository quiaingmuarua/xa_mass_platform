package com.xa.mass.sdk;

import com.xa.mass.sdk.model.AdapterNodeSnapshot;
import com.xa.mass.sdk.model.NodeGroupBindingSnapshot;
import com.xa.mass.sdk.model.WorkerGroupSnapshot;

import java.util.List;

/**
 * Read-only WorkerGroup topology surface for operator views.
 */
public interface WorkerTopologyOperations {

    List<WorkerGroupSnapshot> listWorkerGroups();

    List<AdapterNodeSnapshot> listAdapterNodes();

    List<NodeGroupBindingSnapshot> listNodeGroupBindings();
}
