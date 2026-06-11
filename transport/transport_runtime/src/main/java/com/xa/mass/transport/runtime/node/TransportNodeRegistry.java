package com.xa.mass.transport.runtime.node;

import java.util.List;

/**
 * Shared transport-node runtime view.
 */
public interface TransportNodeRegistry {

    TransportNodePresence register(String transportNodeId, List<String> adapterIds, long connectionCount);

    TransportNodePresence heartbeat(String transportNodeId, List<String> adapterIds, long connectionCount);

    TransportNodePresence releaseRouteOwner(String transportNodeId);

    TransportNodePresence getNode(String transportNodeId);

    List<TransportNodePresence> listNodes();

    default boolean isNodeOnline(String transportNodeId) {
        TransportNodePresence node = getNode(transportNodeId);
        return node != null && node.isOnline(System.currentTimeMillis());
    }
}
