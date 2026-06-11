package com.xa.mass.transport.runtime.worker;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchBatch;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedTaskDispatchHandoff;
import com.xa.mass.transport.runtime.dispatch.RouteTargetedDispatchFixtures;
import com.xa.mass.transport.runtime.node.InMemoryTransportNodeRegistry;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTargetedTaskDispatchSubmitterTest {

    @Test
    void submitsBindingsByResolvedRouteKeyAndActiveRouteConsumers() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "node-1");
        routeOwnerStore.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        InMemoryTransportNodeRegistry nodeRegistry = new InMemoryTransportNodeRegistry();
        nodeRegistry.register("node-1", List.of("websocket"), 1L);
        CapturingHandoff handoff = new CapturingHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        RouteTargetedTaskDispatchSubmitter submitter = new RouteTargetedTaskDispatchSubmitter(
                handoff,
                (binding, context) -> "route-1",
                routeOwnerStore,
                nodeRegistry,
                (task, bindings, detail) -> {
                    compensated.addAll(bindings);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(RouteTargetedDispatchFixtures.context(), List.of(
                RouteTargetedDispatchFixtures.binding("msg-1", "worker-1")
        ));

        assertTrue(compensated.isEmpty());
        assertEquals(1, handoff.submitted.size());
        assertEquals("route-1", handoff.submitted.getFirst().routeKey());
        assertEquals("node-1", handoff.submitted.getFirst().targetTransportNodeId());
        assertEquals("node-1", handoff.submitted.getFirst().deliveryBindings().getFirst().lanePartition());
        assertEquals("worker-1", handoff.submitted.getFirst().deliveryBindings().getFirst().selectedWorkerId());
    }

    @Test
    void splitsGroupRouteBindingsBySelectedWorkerConsumerNode() {
        long now = System.currentTimeMillis();
        List<WorkerDispatchRouteOwner> owners = List.of(
                new WorkerDispatchRouteOwner(
                        "worker-1",
                        "websocket",
                        "group-route",
                        "node-1",
                        "conn-1",
                        now + 30_000L,
                        now
                ),
                new WorkerDispatchRouteOwner(
                        "worker-2",
                        "websocket",
                        "group-route",
                        "node-2",
                        "conn-2",
                        now + 30_000L,
                        now + 1L
                )
        );
        WorkerDispatchRouteOwnerView routeOwnerView = new WorkerDispatchRouteOwnerView() {
            @Override
            public List<WorkerDispatchRouteOwner> currentOwners(String routeKey) {
                return owners.stream()
                        .filter(owner -> owner.routeKey().equals(routeKey))
                        .toList();
            }

            @Override
            public Optional<WorkerDispatchRouteOwner> activeOwnerForSelectedWorker(String adapterId,
                                                                                   String selectedWorkerId) {
                long currentTimeMillis = System.currentTimeMillis();
                return owners.stream()
                        .filter(owner -> owner.isActive(currentTimeMillis))
                        .filter(owner -> owner.adapterId().equals(adapterId))
                        .filter(owner -> owner.workerId().equals(selectedWorkerId))
                        .findFirst();
            }
        };
        InMemoryTransportNodeRegistry nodeRegistry = new InMemoryTransportNodeRegistry();
        nodeRegistry.register("node-1", List.of("websocket"), 1L);
        nodeRegistry.register("node-2", List.of("websocket"), 1L);
        CapturingHandoff handoff = new CapturingHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        RouteTargetedTaskDispatchSubmitter submitter = new RouteTargetedTaskDispatchSubmitter(
                handoff,
                (binding, context) -> "group-route",
                routeOwnerView,
                nodeRegistry,
                (task, bindings, detail) -> {
                    compensated.addAll(bindings);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(RouteTargetedDispatchFixtures.context(), List.of(
                RouteTargetedDispatchFixtures.binding("msg-1", "worker-1"),
                RouteTargetedDispatchFixtures.binding("msg-2", "worker-2")
        ));

        assertTrue(compensated.isEmpty());
        assertEquals(2, handoff.submitted.size());
        assertEquals("node-1", handoff.submitted.get(0).targetTransportNodeId());
        assertEquals("node-1", handoff.submitted.get(0).deliveryBindings().getFirst().lanePartition());
        assertEquals("worker-1", handoff.submitted.get(0).deliveryBindings().getFirst().selectedWorkerId());
        assertEquals(List.of("msg-1"), RouteTargetedDispatchFixtures.messages(handoff.submitted.get(0)));
        assertEquals("node-2", handoff.submitted.get(1).targetTransportNodeId());
        assertEquals("node-2", handoff.submitted.get(1).deliveryBindings().getFirst().lanePartition());
        assertEquals("worker-2", handoff.submitted.get(1).deliveryBindings().getFirst().selectedWorkerId());
        assertEquals(List.of("msg-2"), RouteTargetedDispatchFixtures.messages(handoff.submitted.get(1)));
    }

    @Test
    void compensatesWhenRouteConsumerIsMissing() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "node-1");
        CapturingHandoff handoff = new CapturingHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        RouteTargetedTaskDispatchSubmitter submitter = new RouteTargetedTaskDispatchSubmitter(
                handoff,
                (binding, context) -> "route-1",
                routeOwnerStore,
                null,
                (task, bindings, detail) -> {
                    compensated.addAll(bindings);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(RouteTargetedDispatchFixtures.context(), List.of(
                RouteTargetedDispatchFixtures.binding("msg-1", "worker-1")
        ));

        assertTrue(handoff.submitted.isEmpty());
        assertEquals(List.of("msg-1"), compensated.stream().map(TaskDispatchBinding::messageId).toList());
    }

    @Test
    void compensatesWhenSelectedWorkerIsMissing() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "node-1");
        routeOwnerStore.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        CapturingHandoff handoff = new CapturingHandoff();
        List<TaskDispatchBinding> compensated = new ArrayList<>();
        RouteTargetedTaskDispatchSubmitter submitter = new RouteTargetedTaskDispatchSubmitter(
                handoff,
                (binding, context) -> "route-1",
                routeOwnerStore,
                null,
                (task, bindings, detail) -> {
                    compensated.addAll(bindings);
                    return true;
                }
        );

        submitter.onTaskDispatchBatch(RouteTargetedDispatchFixtures.context(), List.of(
                RouteTargetedDispatchFixtures.binding("msg-1", null)
        ));

        assertTrue(handoff.submitted.isEmpty());
        assertEquals(List.of("msg-1"), compensated.stream().map(TaskDispatchBinding::messageId).toList());
    }

    private static final class CapturingHandoff implements RouteTargetedTaskDispatchHandoff {
        private final List<RouteTargetedTaskDispatchBatch> submitted = new ArrayList<>();

        @Override
        public void submit(RouteTargetedTaskDispatchBatch batch) {
            submitted.add(batch);
        }

        @Override
        public RouteTargetedTaskDispatchBatch poll(long timeoutMillis) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }
}
