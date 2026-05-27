package com.xa.mass.runtime.worker;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Worker resource declaration and dispatch-gate mutation surface.
 */
public interface WorkerResourceRuntime {

    void addWorker(WorkerResourceRecord worker);

    Optional<WorkerResourceRecord> worker(String workerId);

    List<WorkerResourceRecord> workers();

    boolean updateWorker(WorkerResourceRecord worker);

    boolean deleteWorker(String workerId);

    WorkerGroupRecord upsertWorkerGroup(WorkerGroupRecord group);

    Optional<WorkerGroupRecord> workerGroup(String groupId);

    List<WorkerGroupRecord> workerGroups();

    boolean deleteWorkerGroup(String groupId);

    AdapterNodeRecord registerAdapterNode(AdapterNodeRecord adapterNode);

    Optional<AdapterNodeRecord> adapterNode(String adapterNodeId);

    List<AdapterNodeRecord> adapterNodes();

    boolean deleteAdapterNode(String adapterNodeId);

    NodeGroupBindingRecord bindNodeGroup(NodeGroupBindingRecord binding);

    Optional<NodeGroupBindingRecord> nodeGroupBinding(String adapterNodeId, String groupId);

    List<NodeGroupBindingRecord> nodeGroupBindings();

    boolean unbindNodeGroup(String adapterNodeId, String groupId);

    Set<String> groupIdsByAdapterNodeId(String adapterNodeId);

    Set<String> adapterNodeIdsByGroupId(String groupId);

    NodeGroupBindingRecord setNodeGroupBindingEnabled(String adapterNodeId,
                                                      String groupId,
                                                      boolean enabled);

    NodeGroupBindingRecord setNodeGroupBindingDraining(String adapterNodeId,
                                                       String groupId,
                                                       boolean draining);
}
