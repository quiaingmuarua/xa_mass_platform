package com.xa.mass.worker.runtime.resource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only worker resource surface for routing and shell projection callers.
 */
public interface WorkerResourceQueryRuntime {

    Optional<WorkerResourceRecord> worker(String workerId);

    List<WorkerResourceRecord> workers();

    Optional<WorkerGroupRecord> workerGroup(String groupId);

    List<WorkerGroupRecord> workerGroups();

    Optional<AdapterNodeRecord> adapterNode(String adapterNodeId);

    List<AdapterNodeRecord> adapterNodes();

    Optional<NodeGroupBindingRecord> nodeGroupBinding(String adapterNodeId, String groupId);

    List<NodeGroupBindingRecord> nodeGroupBindings();

    Set<String> groupIdsByAdapterNodeId(String adapterNodeId);

    Set<String> adapterNodeIdsByGroupId(String groupId);
}
