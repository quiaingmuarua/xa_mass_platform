package com.xa.mass.worker.runtime.resource;

/**
 * Worker node/group binding mutation surface.
 */
public interface WorkerNodeBindingRuntime {

    NodeGroupBindingRecord bindNodeGroup(NodeGroupBindingRecord binding);

    boolean unbindNodeGroup(String adapterNodeId, String groupId);

    NodeGroupBindingRecord setNodeGroupBindingEnabled(String adapterNodeId,
                                                      String groupId,
                                                      boolean enabled);

    NodeGroupBindingRecord setNodeGroupBindingDraining(String adapterNodeId,
                                                       String groupId,
                                                       boolean draining);
}
