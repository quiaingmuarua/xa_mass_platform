package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.routing.RoutingTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DispatchRoutingBatchTest {

    @Test
    void producerBatchCarriesTargetAndItemsOnly() {
        List<String> componentNames = Arrays.stream(DispatchRoutingBatch.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of("target", "items"), componentNames);
    }

    @Test
    void acceptsOnlyAdapterMailboxTargets() {
        DispatchRoutingItem item = item();

        DispatchRoutingBatch batch = new DispatchRoutingBatch(
                RoutingTarget.adapterMailbox("mailbox-a"),
                List.of(item)
        );

        assertEquals("mailbox-a", batch.adapterMailboxKey());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new DispatchRoutingBatch(RoutingTarget.adapter("adapter-a"), List.of(item))
        );
        assertEquals("dispatch target must be adapter-mailbox", error.getMessage());
    }

    @Test
    void rejectsEmptyOrNullItems() {
        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> new DispatchRoutingBatch(RoutingTarget.adapterMailbox("mailbox-a"), List.of())
        );
        assertEquals("items must not be empty", empty.getMessage());

        IllegalArgumentException nullItem = assertThrows(
                IllegalArgumentException.class,
                () -> new DispatchRoutingBatch(RoutingTarget.adapterMailbox("mailbox-a"), Arrays.asList(item(), null))
        );
        assertEquals("items must not contain null", nullItem.getMessage());
    }

    private static DispatchRoutingItem item() {
        return new DispatchRoutingItem("delivery-1", "worker-1", "{}", "corr-1", 0L, 1L);
    }
}
