package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.route.RouteConsumerEndpoint;
import com.xa.mass.transport.route.SelectedWorkerDeliveryTarget;
import com.xa.mass.transport.route.WorkerDispatchRouteOwner;
import com.xa.mass.transport.route.WorkerDispatchRouteOwnerView;
import com.xa.mass.transport.runtime.node.TransportNodePresence;
import com.xa.mass.transport.runtime.node.TransportNodeRegistry;
import com.xa.mass.transport.runtime.node.TransportNodeState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportAssignedDeliverySubmitterTest {

    @Test
    void missingOwnerEmitsOneFailureAndDoesNotOfferBatch() {
        FakeRouteOwnerView owners = new FakeRouteOwnerView();
        FakeHandoff handoff = new FakeHandoff();
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                owners,
                FakeNodeRegistry.online("node-1"),
                failures
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(command("msg-1", "worker-1")));

        assertEquals(List.of(DispatchOutcomeStatus.NO_ENDPOINT), statuses(outcomes));
        assertEquals(1, failures.events.size());
        assertEquals("worker-1", failures.events.get(0).outcome().getSelectedWorkerId());
        assertEquals(0, handoff.offered.size());
    }

    @Test
    void unavailableTransportNodeEmitsOneFailureAndDoesNotOfferBatch() {
        FakeRouteOwnerView owners = new FakeRouteOwnerView();
        owners.put(owner("worker-1", "node-offline"));
        FakeHandoff handoff = new FakeHandoff();
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                owners,
                FakeNodeRegistry.offline("node-offline"),
                failures
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(command("msg-1", "worker-1")));

        assertEquals(List.of(DispatchOutcomeStatus.NO_ENDPOINT), statuses(outcomes));
        assertEquals(1, failures.events.size());
        assertEquals("worker-1", failures.events.get(0).outcome().getSelectedWorkerId());
        assertEquals(0, handoff.offered.size());
    }

    @Test
    void handoffBackpressureEmitsOneFailure() {
        FakeRouteOwnerView owners = new FakeRouteOwnerView();
        owners.put(owner("worker-1", "node-1"));
        FakeHandoff handoff = new FakeHandoff();
        handoff.backpressure = true;
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                owners,
                FakeNodeRegistry.online("node-1"),
                failures
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(command("msg-1", "worker-1")));

        assertEquals(List.of(DispatchOutcomeStatus.BACKPRESSURE), statuses(outcomes));
        assertEquals(1, failures.events.size());
        assertEquals("worker-1", failures.events.get(0).outcome().getSelectedWorkerId());
    }

    @Test
    void handoffFailureIsConvertedToRetryableOutcome() {
        FakeRouteOwnerView owners = new FakeRouteOwnerView();
        owners.put(owner("worker-1", "node-1"));
        FakeHandoff handoff = new FakeHandoff();
        handoff.failure = new IllegalStateException("redis unavailable");
        RecordingFailureHandler failures = new RecordingFailureHandler();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                owners,
                FakeNodeRegistry.online("node-1"),
                failures
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(command("msg-1", "worker-1")));

        assertEquals(List.of(DispatchOutcomeStatus.UNAVAILABLE), statuses(outcomes));
        assertEquals(1, failures.events.size());
        assertEquals("worker-1", failures.events.get(0).outcome().getSelectedWorkerId());
    }

    @Test
    void groupsCommandsByDeliveryQueueAndTargetTransportNode() {
        FakeRouteOwnerView owners = new FakeRouteOwnerView();
        owners.put(owner("worker-1", "node-1"));
        owners.put(owner("worker-2", "node-1"));
        owners.put(owner("worker-3", "node-2"));
        FakeHandoff handoff = new FakeHandoff();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                owners,
                FakeNodeRegistry.online("node-1", "node-2"),
                new RecordingFailureHandler()
        );

        List<DispatchOutcome> outcomes = submitter.submit(List.of(
                command("msg-1", "worker-1"),
                command("msg-2", "worker-2"),
                command("msg-3", "worker-3")
        ));

        assertEquals(List.of(
                DispatchOutcomeStatus.QUEUED,
                DispatchOutcomeStatus.QUEUED,
                DispatchOutcomeStatus.QUEUED
        ), statuses(outcomes));
        assertEquals(2, handoff.offered.size());
        assertEquals(List.of("msg-1", "msg-2"), DeliveryCommandFixtures.messages(handoff.offered.get(0)));
        assertEquals("node-1", handoff.offered.get(0).targetTransportNodeId());
        assertEquals(List.of("msg-3"), DeliveryCommandFixtures.messages(handoff.offered.get(1)));
        assertEquals("node-2", handoff.offered.get(1).targetTransportNodeId());
    }

    @Test
    void ownerResolutionEnrichesTargetWithoutChangingSelectedWorker() {
        FakeRouteOwnerView owners = new FakeRouteOwnerView();
        owners.put(owner("worker-selected", "node-1"));
        FakeHandoff handoff = new FakeHandoff();
        TransportAssignedDeliverySubmitter submitter = new TransportAssignedDeliverySubmitter(
                handoff,
                owners,
                FakeNodeRegistry.online("node-1"),
                new RecordingFailureHandler()
        );

        DeliveryCommand command = command("msg-1", "worker-selected");

        submitter.submit(List.of(command));

        DeliveryCommand offered = handoff.offered.get(0).items().get(0);
        assertEquals("worker-selected", offered.getSelectedWorkerId());
        assertEquals("node-1", handoff.offered.get(0).targetTransportNodeId());
    }

    private static DeliveryCommand command(String messageId, String selectedWorkerId) {
        return DeliveryCommandFixtures.command(messageId, selectedWorkerId, null, "route-" + selectedWorkerId);
    }

    private static SelectedWorkerDeliveryTarget owner(String workerId, String transportNodeId) {
        return new SelectedWorkerDeliveryTarget(
                "bucket-1",
                workerId,
                transportNodeId
        );
    }

    private static List<DispatchOutcomeStatus> statuses(List<DispatchOutcome> outcomes) {
        return outcomes.stream().map(DispatchOutcome::getStatus).toList();
    }

    private static final class FakeRouteOwnerView implements WorkerDispatchRouteOwnerView {
        private final Map<String, SelectedWorkerDeliveryTarget> ownersByWorkerId = new LinkedHashMap<>();

        private void put(SelectedWorkerDeliveryTarget owner) {
            ownersByWorkerId.put(owner.selectedWorkerId(), owner);
        }

        @Override
        public List<WorkerDispatchRouteOwner> currentOwners(String routeKey) {
            return List.of();
        }

        @Override
        public Optional<SelectedWorkerDeliveryTarget> targetForSelectedWorker(String deliveryBucketId,
                                                                              String selectedWorkerId) {
            SelectedWorkerDeliveryTarget owner = ownersByWorkerId.get(selectedWorkerId);
            if (owner == null || !owner.deliveryBucketId().equals(deliveryBucketId)) {
                return Optional.empty();
            }
            return Optional.of(owner);
        }

        @Override
        public Optional<RouteConsumerEndpoint> endpointForSelectedWorker(String deliveryBucketId, String selectedWorkerId) {
            return Optional.empty();
        }
    }

    private static final class FakeHandoff implements TransportDeliveryCommandHandoff {
        private final List<DeliveryCommandBatch> offered = new ArrayList<>();
        private boolean backpressure;
        private RuntimeException failure;

        @Override
        public List<DispatchOutcome> offer(DeliveryCommandBatch batch) {
            if (failure != null) {
                throw failure;
            }
            offered.add(batch);
            if (backpressure) {
                return batch.items().stream()
                        .map(item -> DispatchOutcome.fromCommand(
                                item,
                                DispatchOutcomeStatus.BACKPRESSURE,
                                true,
                                "test backpressure"))
                        .toList();
            }
            return batch.items().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.QUEUED,
                            false,
                            null))
                    .toList();
        }

        @Override
        public DeliveryCommandBatch poll(long timeoutMillis) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }

    private static final class RecordingFailureHandler implements TransportDeliveryFailureHandler {
        private final List<TransportDeliveryFailureEvent> events = new ArrayList<>();

        @Override
        public boolean handle(TransportDeliveryFailureEvent event) {
            events.add(event);
            return true;
        }
    }

    private static final class FakeNodeRegistry implements TransportNodeRegistry {
        private final Map<String, TransportNodePresence> nodes = new LinkedHashMap<>();

        private static FakeNodeRegistry online(String... transportNodeIds) {
            FakeNodeRegistry registry = new FakeNodeRegistry();
            for (String transportNodeId : transportNodeIds) {
                registry.nodes.put(transportNodeId, node(transportNodeId, TransportNodeState.ONLINE));
            }
            return registry;
        }

        private static FakeNodeRegistry offline(String transportNodeId) {
            FakeNodeRegistry registry = new FakeNodeRegistry();
            registry.nodes.put(transportNodeId, node(transportNodeId, TransportNodeState.OFFLINE));
            return registry;
        }

        private static TransportNodePresence node(String transportNodeId, TransportNodeState state) {
            long now = System.currentTimeMillis();
            return new TransportNodePresence(
                    transportNodeId,
                    List.of("websocket"),
                    state,
                    now,
                    now + 30_000L,
                    now,
                    1L
            );
        }

        @Override
        public TransportNodePresence register(String transportNodeId, List<String> adapterIds, long connectionCount) {
            throw unsupported();
        }

        @Override
        public TransportNodePresence heartbeat(String transportNodeId, List<String> adapterIds, long connectionCount) {
            throw unsupported();
        }

        @Override
        public TransportNodePresence releaseRouteOwner(String transportNodeId) {
            throw unsupported();
        }

        @Override
        public TransportNodePresence getNode(String transportNodeId) {
            return nodes.get(transportNodeId);
        }

        @Override
        public List<TransportNodePresence> listNodes() {
            return List.copyOf(nodes.values());
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not needed by assigned delivery submitter test");
        }
    }
}
