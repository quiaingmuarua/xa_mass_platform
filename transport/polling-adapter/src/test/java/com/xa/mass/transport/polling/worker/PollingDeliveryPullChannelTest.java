package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.polling.runtime.PollingTransportAdapterBootstrap;
import com.xa.mass.transport.runtime.delivery.AdapterPullDeliveryBuffer;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PollingDeliveryPullChannelTest {

    private static final String BUCKET = "bucket-1";

    @Test
    void sharedBucketDoesNotCrossConsumeSelectedWorkerItems() {
        AdapterPullDeliveryBuffer deliveryBuffer = deliveryBuffer();
        PollingDeliveryExecutor executor = new PollingDeliveryExecutor("polling-default", deliveryBuffer);
        PollingDeliveryPullChannel pullChannel = new PollingDeliveryPullChannel(
                PollingTransportAdapterBootstrap.DEFAULT_ADAPTER_ID,
                deliveryBuffer
        );
        executor.dispatch(List.of(request("msg-2", "worker-2")));

        assertEquals(DeliveryPullStatus.EMPTY,
                pullChannel.pollDeliveryMessagesResult(BUCKET, "worker-1", 10, 0).getStatus());
        assertEquals(List.of("msg-2"), pullChannel.pollDeliveryMessages(BUCKET, "worker-2", 10, 0).stream()
                .map(PulledDeliveryMessage::getPayload)
                .map(this::messageId)
                .toList());
    }

    @Test
    void pollResultPreservesDeliveredAndInvalidRequestStatuses() {
        AdapterPullDeliveryBuffer deliveryBuffer = deliveryBuffer();
        PollingDeliveryExecutor executor = new PollingDeliveryExecutor("polling-default", deliveryBuffer);
        PollingDeliveryPullChannel pullChannel = new PollingDeliveryPullChannel(
                PollingTransportAdapterBootstrap.DEFAULT_ADAPTER_ID,
                deliveryBuffer
        );
        executor.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(DeliveryPullStatus.DELIVERED,
                pullChannel.pollDeliveryMessagesResult(BUCKET, "worker-1", 1, 0).getStatus());
        assertEquals(DeliveryPullStatus.INVALID_REQUEST,
                pullChannel.pollDeliveryMessagesResult(BUCKET, " ", 10, 0).getStatus());
        assertEquals(DeliveryPullStatus.INVALID_REQUEST,
                pullChannel.pollDeliveryMessagesResult(BUCKET, "worker-1", 0, 0).getStatus());
    }

    private AdapterPullDeliveryBuffer deliveryBuffer() {
        return new AdapterPullDeliveryBuffer(
                PollingTransportAdapterBootstrap.DEFAULT_ADAPTER_ID,
                new TransportDeliveryService(new InMemoryTransportDeliveryStore())
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

    private String messageId(String payload) {
        return payload.replace("{\"messageId\":\"", "").replace("\"}", "");
    }
}
