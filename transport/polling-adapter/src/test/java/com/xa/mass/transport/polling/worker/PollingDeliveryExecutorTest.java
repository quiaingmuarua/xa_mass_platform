package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.polling.runtime.PollingTransportAdapterBootstrap;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.AdapterPullDeliveryBuffer;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingDeliveryExecutorTest {

    private static final String BUCKET = "bucket-1";
    private static final int MAX_INBOX_SIZE = 10_000;

    @Test
    void dispatchQueuesItemsForPollingWorker() {
        Fixture fixture = fixture();

        List<DispatchOutcome> outcomes = fixture.executor.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcomes.get(0).getStatus());
        assertEquals(DeliveryPullStatus.DELIVERED,
                fixture.pullChannel.pollDeliveryMessagesResult(BUCKET, "worker-1", 1, 0).getStatus());
    }

    @Test
    void dispatchRejectsMissingWorkerIdAsInvalidItem() {
        Fixture fixture = fixture();

        List<DispatchOutcome> outcomes = fixture.executor.dispatch(Collections.singletonList(null));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.INVALID, outcomes.get(0).getStatus());
        assertTrue(fixture.pullChannel.pollDeliveryMessages(BUCKET, "worker-1", 10, 0).isEmpty());
        assertEquals(DeliveryPullStatus.EMPTY,
                fixture.pullChannel.pollDeliveryMessagesResult(BUCKET, "worker-1", 10, 0).getStatus());
    }

    @Test
    void dispatchReportsBackpressureWhenBucketQueueIsFull() {
        Fixture fixture = fixture();
        List<DeliveryCommand> items = new ArrayList<>();
        for (int i = 0; i < MAX_INBOX_SIZE + 1; i++) {
            items.add(request("msg-" + i, "worker-1"));
        }

        List<DispatchOutcome> outcomes = fixture.executor.dispatch(items);

        assertEquals(MAX_INBOX_SIZE + 1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE,
                outcomes.get(outcomes.size() - 1).getStatus());
        assertTrue(outcomes.get(outcomes.size() - 1).isRetryable());
        assertEquals(MAX_INBOX_SIZE,
                fixture.pullChannel.pollDeliveryMessages(BUCKET, "worker-1", MAX_INBOX_SIZE + 10, 0).size());
    }

    private Fixture fixture() {
        AdapterPullDeliveryBuffer deliveryBuffer = new AdapterPullDeliveryBuffer(
                PollingTransportAdapterBootstrap.DEFAULT_ADAPTER_ID,
                new TransportDeliveryService(
                new InMemoryTransportDeliveryStore(
                        InMemoryTransportDeliveryStore.DEFAULT_MAX_QUEUED_ITEMS,
                        MAX_INBOX_SIZE
                )
                )
        );
        return new Fixture(
                new PollingDeliveryExecutor(PollingTransportAdapterBootstrap.DEFAULT_ADAPTER_ID, deliveryBuffer),
                new PollingDeliveryPullChannel(PollingTransportAdapterBootstrap.DEFAULT_ADAPTER_ID, deliveryBuffer)
        );
    }

    private DeliveryCommand request(String messageId, String workerId) {
        return new DeliveryCommand(
                "delivery-" + messageId,
                BUCKET,
                workerId,
                "{\"messageId\":\"" + messageId + "\"}",
                "corr-" + messageId,
                0L,
                1L
        );
    }

    private record Fixture(PollingDeliveryExecutor executor, PollingDeliveryPullChannel pullChannel) {
    }
}
